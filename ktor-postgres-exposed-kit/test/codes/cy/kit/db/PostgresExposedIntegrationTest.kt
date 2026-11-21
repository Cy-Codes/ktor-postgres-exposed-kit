package codes.cy.kit.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers
class PostgresExposedIntegrationTest {

    @Test
    fun `checkSchemaReady returns false before migrations and true after Flyway migration`() = runTest {
        val migratorConfig = DatabaseConfig(
            jdbcUrl = postgres.jdbcUrl,
            username = MIGRATOR_ROLE,
            password = ROLE_PASSWORD,
        )
        DatabaseFactory.connect(migratorConfig).use { managed ->
            val unmigrated = managed.checkSchemaReady(schema = UNMIGRATED_SCHEMA)
            assertFalse(unmigrated.ready)
            assertEquals(0, unmigrated.appliedMigrationCount)

            val migrated = managed.checkSchemaReady(schema = APP_SCHEMA)
            assertTrue(migrated.ready)
            assertTrue(migrated.appliedMigrationCount >= EXPECTED_MIGRATION_COUNT)

            val stricter = managed.checkSchemaReady(
                schema = APP_SCHEMA,
                minimumAppliedMigrations = migrated.appliedMigrationCount + 1,
            )
            assertFalse(stricter.ready)
            assertEquals(migrated.appliedMigrationCount, stricter.appliedMigrationCount)
        }
    }

    @Test
    fun `FlywayMigrator executes advisory-locked migrations cleanly under concurrent runners`() = runTest {
        val migratorConfig = DatabaseConfig(
            jdbcUrl = postgres.jdbcUrl,
            username = MIGRATOR_ROLE,
            password = ROLE_PASSWORD,
            maximumPoolSize = CONCURRENT_POOL_SIZE,
            poolName = "concurrent-migrator-pool",
        )

        DatabaseFactory.createDataSource(migratorConfig).use { dataSource ->
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE SCHEMA IF NOT EXISTS $CONCURRENT_LOCK_SCHEMA")
                }
                if (!conn.autoCommit) {
                    conn.commit()
                }
            }

            val results = (1..CONCURRENT_MIGRATOR_COUNT).map {
                async(Dispatchers.IO) {
                    FlywayMigrator.migrate(
                        dataSource = dataSource,
                        locations = listOf(CONCURRENT_MIGRATIONS_LOCATION),
                        schemas = listOf(CONCURRENT_LOCK_SCHEMA),
                        defaultSchema = CONCURRENT_LOCK_SCHEMA,
                        transactionalLock = true,
                    )
                }
            }.awaitAll()

            assertEquals(CONCURRENT_MIGRATOR_COUNT, results.size)
            assertEquals(1, results.sumOf { result -> result.migrationsExecuted })

            val readiness = DatabaseFactory.checkSchemaReady(
                dataSource = dataSource,
                schema = CONCURRENT_LOCK_SCHEMA,
            )
            assertTrue(readiness.ready)
            assertTrue(readiness.appliedMigrationCount >= 1)

            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM $CONCURRENT_LOCK_SCHEMA.lock_audit").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(1, rs.getInt(FIRST_COLUMN_INDEX))
                    }
                }
            }
        }
    }

    @Test
    fun `dbQuery enforces Dispatchers IO offloading, non-autocommit, and REPEATABLE READ isolation`() = runTest {
        val rwConfig = DatabaseConfig(
            jdbcUrl = postgres.jdbcUrl,
            username = READ_WRITE_ROLE,
            password = ROLE_PASSWORD,
            poolName = "rw-pool",
        )

        DatabaseFactory.connect(rwConfig).use { managed ->
            assertNotNull(managed.hikariDataSource)

            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, CALLER_THREAD_NAME)
            }.asCoroutineDispatcher().use { callerDispatcher ->
                val txDetails = withContext(callerDispatcher) {
                    managed.dbQuery {
                        val rawConn = connection.connection as Connection
                        val isolationSetting = rawConn.prepareStatement("SHOW transaction_isolation").use { stmt ->
                            stmt.executeQuery().use { rs ->
                                rs.next()
                                rs.getString(FIRST_COLUMN_INDEX)
                            }
                        }

                        val itemId = Uuid.random()
                        val itemSlug = "kit-item-$itemId"
                        ItemsTable.insert {
                            it[id] = itemId
                            it[slug] = itemSlug
                            it[title] = "Verified Exposed 1.0 IO Row"
                        }

                        val loadedTitle = ItemsTable
                            .selectAll()
                            .where { ItemsTable.slug eq itemSlug }
                            .single()[ItemsTable.title]

                        TransactionDetails(
                            threadName = Thread.currentThread().name,
                            autoCommit = rawConn.autoCommit,
                            jdbcIsolation = rawConn.transactionIsolation,
                            pgIsolation = isolationSetting,
                            loadedTitle = loadedTitle,
                        )
                    }
                }

                assertFalse(
                    txDetails.threadName.startsWith(CALLER_THREAD_NAME),
                    "Expected dbQuery to offload off caller thread '$CALLER_THREAD_NAME', ran on '${txDetails.threadName}'.",
                )
                assertFalse(txDetails.autoCommit)
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ, txDetails.jdbcIsolation)
                assertEquals(PG_REPEATABLE_READ_NAME, txDetails.pgIsolation)
                assertEquals("Verified Exposed 1.0 IO Row", txDetails.loadedTitle)
            }
        }
    }

    @Test
    fun `executeUpdateNoReturning allows INSERT-only role without SELECT privilege while RETURN_GENERATED_KEYS fails`() = runTest {
        val roConfig = DatabaseConfig(
            jdbcUrl = postgres.jdbcUrl,
            username = READ_ONLY_ROLE,
            password = ROLE_PASSWORD,
            poolName = "ro-pool",
        )
        val rwConfig = DatabaseConfig(
            jdbcUrl = postgres.jdbcUrl,
            username = READ_WRITE_ROLE,
            password = ROLE_PASSWORD,
            poolName = "rw-verify-pool",
        )

        DatabaseFactory.connect(roConfig).use { roDb ->
            assertTrue(roDb.checkSchemaReady(schema = APP_SCHEMA).ready)

            val standaloneAffected = roDb.executeUpdateNoReturning(INSERT_EVENT_SQL) { stmt ->
                stmt.setString(1, "standalone_append")
                stmt.setString(2, "no-returning-direct")
            }
            assertEquals(1, standaloneAffected)

            val txAffected = roDb.dbQuery {
                executeUpdateNoReturning(INSERT_EVENT_SQL) { stmt ->
                    stmt.setString(1, "exposed_tx_append")
                    stmt.setString(2, "no-returning-in-tx")
                }
            }
            assertEquals(1, txAffected)

            // Prove PgJDBC RETURN_GENERATED_KEYS fails on an INSERT-only table without SELECT grant
            DriverManager.getConnection(postgres.jdbcUrl, READ_ONLY_ROLE, ROLE_PASSWORD).use { rawConn ->
                val pgError = assertFailsWith<SQLException> {
                    rawConn.prepareStatement(INSERT_EVENT_SQL, Statement.RETURN_GENERATED_KEYS).use { stmt ->
                        stmt.setString(1, "blocked_returning")
                        stmt.setString(2, "should-fail-42501")
                        stmt.executeUpdate()
                    }
                }
                assertEquals(PG_INSUFFICIENT_PRIVILEGE, pgError.sqlState)

                val selectDenied = assertFailsWith<SQLException> {
                    rawConn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT COUNT(*) FROM $TELEMETRY_SCHEMA.events")
                    }
                }
                assertEquals(PG_INSUFFICIENT_PRIVILEGE, selectDenied.sqlState)

                val writeDenied = assertFailsWith<SQLException> {
                    rawConn.createStatement().use { stmt ->
                        stmt.executeUpdate("DELETE FROM $APP_SCHEMA.items")
                    }
                }
                assertEquals(PG_INSUFFICIENT_PRIVILEGE, writeDenied.sqlState)
            }
        }

        // Verify via READ_WRITE role that both NO_GENERATED_KEYS inserts committed durably
        DatabaseFactory.connect(rwConfig).use { rwDb ->
            val recordedEvents = rwDb.dbQuery {
                val rawConn = connection.connection as Connection
                rawConn.prepareStatement(
                    "SELECT event_name FROM $TELEMETRY_SCHEMA.events ORDER BY id ASC",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(FIRST_COLUMN_INDEX) else null }
                            .toList()
                    }
                }
            }
            assertTrue(recordedEvents.contains("standalone_append"))
            assertTrue(recordedEvents.contains("exposed_tx_append"))
        }
    }

    private data class TransactionDetails(
        val threadName: String,
        val autoCommit: Boolean,
        val jdbcIsolation: Int,
        val pgIsolation: String,
        val loadedTitle: String,
    )

    private object ItemsTable : Table("$APP_SCHEMA.items") {
        val id = uuid("id")
        val slug = text("slug")
        val title = text("title")
        override val primaryKey: PrimaryKey = PrimaryKey(id)
    }

    companion object {
        private const val DATABASE_NAME: String = "kit_test_db"
        private const val MIGRATOR_ROLE: String = "kit_migrator"
        private const val READ_WRITE_ROLE: String = "kit_read_write"
        private const val READ_ONLY_ROLE: String = "kit_read_only"
        private const val ROLE_PASSWORD: String = "kit_tc_secret_pw"
        private const val APP_SCHEMA: String = "app_kit"
        private const val TELEMETRY_SCHEMA: String = "telemetry_kit"
        private const val UNMIGRATED_SCHEMA: String = "unmigrated_kit"
        private const val CONCURRENT_LOCK_SCHEMA: String = "concurrent_lock_kit"
        private const val MIGRATIONS_LOCATION: String = "classpath:db/migrations"
        private const val CONCURRENT_MIGRATIONS_LOCATION: String = "classpath:db/concurrent_migrations"
        private const val EXPECTED_MIGRATION_COUNT: Int = 1
        private const val CONCURRENT_MIGRATOR_COUNT: Int = 4
        private const val CONCURRENT_POOL_SIZE: Int = 6
        private const val FIRST_COLUMN_INDEX: Int = 1
        private const val CALLER_THREAD_NAME: String = "kit-caller-thread"
        private const val PG_REPEATABLE_READ_NAME: String = "repeatable read"
        private const val PG_INSUFFICIENT_PRIVILEGE: String = "42501"
        private const val INSERT_EVENT_SQL: String =
            "INSERT INTO telemetry_kit.events (event_name, payload) VALUES (?, ?)"

        @Container
        @JvmField
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16.8-alpine").apply {
            withDatabaseName(DATABASE_NAME)
            withUsername("postgres")
            withPassword("tc_superuser_pw")
        }

        @JvmStatic
        @BeforeAll
        fun provisionRolesAndRunMigrations() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        DO $$
                        BEGIN
                            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$MIGRATOR_ROLE') THEN
                                CREATE ROLE $MIGRATOR_ROLE WITH LOGIN PASSWORD '$ROLE_PASSWORD' CREATEROLE;
                            END IF;
                            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$READ_WRITE_ROLE') THEN
                                CREATE ROLE $READ_WRITE_ROLE WITH LOGIN PASSWORD '$ROLE_PASSWORD';
                            END IF;
                            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$READ_ONLY_ROLE') THEN
                                CREATE ROLE $READ_ONLY_ROLE WITH LOGIN PASSWORD '$ROLE_PASSWORD';
                            END IF;
                        END $$;
                        GRANT CONNECT, CREATE ON DATABASE $DATABASE_NAME TO $MIGRATOR_ROLE;
                        GRANT CONNECT ON DATABASE $DATABASE_NAME TO $READ_WRITE_ROLE, $READ_ONLY_ROLE;
                        """.trimIndent(),
                    )
                }
            }

            val result = FlywayMigrator.migrate(
                config = DatabaseConfig(
                    jdbcUrl = postgres.jdbcUrl,
                    username = MIGRATOR_ROLE,
                    password = ROLE_PASSWORD,
                ),
                locations = listOf(MIGRATIONS_LOCATION),
                schemas = listOf(APP_SCHEMA, TELEMETRY_SCHEMA),
                defaultSchema = APP_SCHEMA,
                transactionalLock = true,
            )
            assertEquals(EXPECTED_MIGRATION_COUNT, result.migrationsExecuted)
        }
    }
}
