package codes.cy.kit.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import javax.sql.DataSource
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig as ExposedDatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Lifecycle-managed PostgreSQL connection pool and Exposed 1.0 [Database] handle.
 *
 * Closes the underlying [dataSource] deterministically when [close] is invoked (for example via
 * `.use { }` or server shutdown hooks).
 *
 * @property dataSource Underlying JDBC [DataSource] (a [HikariDataSource] when created via
 *   [DatabaseFactory.connect] with [DatabaseConfig]).
 * @property database Connected Exposed 1.0 [Database] bound to [dataSource].
 */
public class ManagedDatabase internal constructor(
    public val dataSource: DataSource,
    public val database: Database,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val onClose: () -> Unit,
) : AutoCloseable {

    /**
     * Returns [dataSource] as [HikariDataSource] when backed by HikariCP, or `null` otherwise.
     */
    public val hikariDataSource: HikariDataSource?
        get() = dataSource as? HikariDataSource

    /**
     * Side-effecting command/query bridge running [block] inside an Exposed 1.0
     * [suspendTransaction] dispatched onto [ioContext] (defaults to [Dispatchers.IO]).
     */
    public suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        DatabaseFactory.dbQuery(
            database = database,
            ioContext = ioContext,
            block = block,
        )

    /**
     * Side-effecting DML command executing [sql] with [Statement.NO_GENERATED_KEYS] on
     * [ioContext] so PostgreSQL JDBC never appends `RETURNING *` on insert-only roles.
     *
     * @param sql Parameterized SQL DML statement.
     * @param bind Parameter binding callback executed on the prepared statement.
     * @return Number of affected rows reported by [PreparedStatement.executeUpdate].
     */
    public suspend fun executeUpdateNoReturning(
        sql: String,
        bind: (PreparedStatement) -> Unit = {},
    ): Int =
        DatabaseFactory.executeUpdateNoReturning(
            dataSource = dataSource,
            sql = sql,
            ioContext = ioContext,
            bind = bind,
        )

    /**
     * Pure readiness query inspecting `flyway_schema_history` for successful migrations.
     *
     * @param schema Optional schema qualifier where `flyway_schema_history` resides; when `null`,
     *   resolves via the PostgreSQL connection `search_path`.
     * @param minimumAppliedMigrations Minimum count of successful migrations required for
     *   [SchemaReadinessStatus.ready] to be `true`.
     * @return Immutable [SchemaReadinessStatus] (`ready = false` when unavailable or unmigrated).
     */
    public suspend fun checkSchemaReady(
        schema: String? = null,
        minimumAppliedMigrations: Int = DatabaseFactory.DEFAULT_MIN_APPLIED_MIGRATIONS,
    ): SchemaReadinessStatus =
        DatabaseFactory.checkSchemaReady(
            dataSource = dataSource,
            schema = schema,
            minimumAppliedMigrations = minimumAppliedMigrations,
            ioContext = ioContext,
        )

    /**
     * Side-effecting lifecycle cleanup closing the underlying connection pool or data source.
     */
    override fun close(): Unit = onClose()
}

/**
 * Stateless factory and JDBC/Exposed 1.0 IO bridge utilities.
 *
 * Enforces `isAutoCommit = false`, `TRANSACTION_REPEATABLE_READ` isolation by default,
 * [Dispatchers.IO] offloading for blocking JDBC transactions, and [Statement.NO_GENERATED_KEYS]
 * execution for least-privilege PostgreSQL `INSERT`-only roles.
 */
public object DatabaseFactory {
    /** Default minimum number of applied Flyway migrations required for schema readiness. */
    public const val DEFAULT_MIN_APPLIED_MIGRATIONS: Int = 1

    private const val FIRST_COLUMN_INDEX: Int = 1
    private const val ZERO_MIGRATIONS: Int = 0
    private const val FLYWAY_HISTORY_TABLE: String = "flyway_schema_history"
    private val SQL_IDENTIFIER_REGEX: Regex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /**
     * Side-effecting factory creating and validating a [HikariDataSource] from [config].
     *
     * Configures `isAutoCommit = false` and [DatabaseConfig.transactionIsolationLevel]
     * (`TRANSACTION_REPEATABLE_READ` by default). Callers are responsible for closing the returned
     * [HikariDataSource].
     */
    public fun createDataSource(config: DatabaseConfig): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            driverClassName = config.driverClassName
            jdbcUrl = config.jdbcUrl
            username = config.username
            password = config.password
            maximumPoolSize = config.maximumPoolSize
            config.minimumIdle?.let { minimumIdle = it }
            connectionTimeout = config.connectionTimeoutMs
            idleTimeout = config.idleTimeoutMs
            maxLifetime = config.maxLifetimeMs
            isAutoCommit = false
            transactionIsolation = DatabaseConfig.resolveTransactionIsolationName(
                config.transactionIsolationLevel,
            )
            config.poolName?.takeIf { it.isNotBlank() }?.let { poolName = it }
            validate()
        }
        return HikariDataSource(hikariConfig)
    }

    /**
     * Side-effecting factory opening a HikariCP pool from [config], binding Exposed 1.0
     * [Database] to it, and returning a [ManagedDatabase] handle.
     *
     * @param config Immutable PostgreSQL and pool settings.
     * @param ioContext Coroutine context used for blocking JDBC I/O (defaults to [Dispatchers.IO]).
     */
    public fun connect(
        config: DatabaseConfig,
        ioContext: CoroutineContext = Dispatchers.IO,
    ): ManagedDatabase {
        val dataSource = createDataSource(config)
        val database = runCatching {
            Database.connect(
                datasource = dataSource,
                databaseConfig = ExposedDatabaseConfig {
                    defaultIsolationLevel = config.transactionIsolationLevel
                },
            )
        }.getOrElse { ex ->
            runCatching { dataSource.close() }
            throw ex
        }
        return ManagedDatabase(
            dataSource = dataSource,
            database = database,
            ioContext = ioContext,
            onClose = dataSource::close,
        )
    }

    /**
     * Side-effecting factory wrapping an existing [dataSource] in an Exposed 1.0 [Database] and
     * [ManagedDatabase] handle. Closing the handle closes [dataSource] if it implements
     * [AutoCloseable].
     */
    public fun connect(
        dataSource: DataSource,
        ioContext: CoroutineContext = Dispatchers.IO,
    ): ManagedDatabase {
        val database = Database.connect(datasource = dataSource)
        return ManagedDatabase(
            dataSource = dataSource,
            database = database,
            ioContext = ioContext,
            onClose = { (dataSource as? AutoCloseable)?.close() },
        )
    }

    /**
     * Side-effecting transaction bridge dispatching Exposed 1.0 [suspendTransaction] onto
     * [ioContext] (defaults to [Dispatchers.IO]) so Ktor event-loop threads never block on JDBC.
     */
    public suspend fun <T> dbQuery(
        database: Database,
        ioContext: CoroutineContext = Dispatchers.IO,
        block: suspend JdbcTransaction.() -> T,
    ): T =
        withContext(ioContext) {
            suspendTransaction(db = database) {
                block()
            }
        }

    /**
     * Side-effecting DML command executing [sql] against [dataSource] with
     * [Statement.NO_GENERATED_KEYS] on [ioContext], committing when `autoCommit == false` and
     * rolling back on failure.
     *
     * Prevents PgJDBC from appending implicit `RETURNING *`, which would fail with SQLState `42501`
     * on PostgreSQL roles holding `INSERT` without `SELECT`.
     */
    public suspend fun executeUpdateNoReturning(
        dataSource: DataSource,
        sql: String,
        ioContext: CoroutineContext = Dispatchers.IO,
        bind: (PreparedStatement) -> Unit = {},
    ): Int {
        require(sql.isNotBlank()) { "SQL statement must not be blank." }
        return withContext(ioContext) {
            dataSource.connection.use { conn ->
                try {
                    val affected = prepareAndExecuteNoReturning(conn = conn, sql = sql, bind = bind)
                    if (!conn.autoCommit) {
                        conn.commit()
                    }
                    affected
                } catch (ex: Throwable) {
                    if (!conn.autoCommit) {
                        runCatching { conn.rollback() }
                    }
                    throw ex
                }
            }
        }
    }

    /**
     * Pure readiness query checking `flyway_schema_history` on [dataSource] for at least
     * [minimumAppliedMigrations] successful migrations.
     *
     * Never throws on SQL/connectivity failures (returns `ready = false, appliedMigrationCount = 0`),
     * while rethrowing coroutine [CancellationException] and failing fast on invalid arguments.
     *
     * @param dataSource Target JDBC [DataSource].
     * @param schema Optional schema qualifier containing `flyway_schema_history`. Must match
     *   `^[A-Za-z_][A-Za-z0-9_]*$` when non-null.
     * @param minimumAppliedMigrations Minimum count of `success = TRUE` rows required for readiness.
     * @param ioContext Coroutine context used for blocking JDBC query (defaults to [Dispatchers.IO]).
     */
    public suspend fun checkSchemaReady(
        dataSource: DataSource,
        schema: String? = null,
        minimumAppliedMigrations: Int = DEFAULT_MIN_APPLIED_MIGRATIONS,
        ioContext: CoroutineContext = Dispatchers.IO,
    ): SchemaReadinessStatus {
        require(minimumAppliedMigrations > ZERO_MIGRATIONS) {
            "minimumAppliedMigrations must be positive, got $minimumAppliedMigrations."
        }
        val readinessSql = buildReadinessSql(schema)

        return withContext(ioContext) {
            try {
                dataSource.connection.use { conn ->
                    conn.prepareStatement(readinessSql).use { stmt ->
                        stmt.executeQuery().use { rs ->
                            val count = if (rs.next()) rs.getInt(FIRST_COLUMN_INDEX) else ZERO_MIGRATIONS
                            SchemaReadinessStatus(
                                ready = count >= minimumAppliedMigrations,
                                appliedMigrationCount = count,
                            )
                        }
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Throwable) {
                SchemaReadinessStatus(ready = false, appliedMigrationCount = ZERO_MIGRATIONS)
            }
        }
    }

    internal fun prepareAndExecuteNoReturning(
        conn: Connection,
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): Int {
        require(sql.isNotBlank()) { "SQL statement must not be blank." }
        return conn.prepareStatement(sql, Statement.NO_GENERATED_KEYS).use { stmt ->
            bind(stmt)
            stmt.executeUpdate()
        }
    }

    private fun buildReadinessSql(schema: String?): String {
        val tableRef = if (schema != null) {
            require(SQL_IDENTIFIER_REGEX.matches(schema)) {
                "Unsafe SQL schema identifier '$schema'."
            }
            "\"$schema\".\"$FLYWAY_HISTORY_TABLE\""
        } else {
            FLYWAY_HISTORY_TABLE
        }
        return "SELECT COUNT(*) FROM $tableRef WHERE success = TRUE"
    }
}

/**
 * Side-effecting DML helper executing [sql] inside the receiver Exposed [JdbcTransaction] using
 * [Statement.NO_GENERATED_KEYS].
 *
 * Lifecycle of the enclosing transaction connection (commit/rollback/close) remains owned by
 * Exposed's transaction scope.
 *
 * @param sql Parameterized SQL DML statement.
 * @param bind Optional [PreparedStatement] parameter binding lambda.
 * @return Number of affected rows reported by [PreparedStatement.executeUpdate].
 */
public fun JdbcTransaction.executeUpdateNoReturning(
    sql: String,
    bind: (PreparedStatement) -> Unit = {},
): Int {
    val rawConnection = connection.connection as? Connection
        ?: error("Exposed transaction does not expose a java.sql.Connection: ${connection.connection::class.java.name}")
    return DatabaseFactory.prepareAndExecuteNoReturning(
        conn = rawConnection,
        sql = sql,
        bind = bind,
    )
}
