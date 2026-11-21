package codes.cy.kit.db

import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseConfigTest {
    @Test
    fun `resolves distinct credentials per role from environment map`() {
        val env = mapOf(
            DatabaseConfig.ENV_POSTGRES_HOST to "db.internal",
            DatabaseConfig.ENV_POSTGRES_PORT to "5433",
            DatabaseConfig.ENV_POSTGRES_DB to "service_prod",
            "DB_READ_ONLY_USER" to "svc_ro",
            "DB_READ_ONLY_PASSWORD" to "ro_secret_123",
            "DB_READ_WRITE_USER" to "svc_rw",
            "DB_READ_WRITE_PASSWORD" to "rw_secret_456",
            "DB_MIGRATOR_USER" to "svc_mig",
            "DB_MIGRATOR_PASSWORD" to "mig_secret_789",
            DatabaseConfig.ENV_DB_MAX_POOL_SIZE to "24",
            DatabaseConfig.ENV_DB_CONNECTION_TIMEOUT_MS to "4500",
        )

        val readOnly = DatabaseConfig.fromEnv(DatabaseRole.READ_ONLY, env = env)
        assertEquals(
            "jdbc:postgresql://db.internal:5433/service_prod?reWriteBatchedInserts=true",
            readOnly.jdbcUrl,
        )
        assertEquals("svc_ro", readOnly.username)
        assertEquals("ro_secret_123", readOnly.password)
        assertEquals(24, readOnly.maximumPoolSize)
        assertEquals(4500L, readOnly.connectionTimeoutMs)
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, readOnly.transactionIsolationLevel)

        val readWrite = DatabaseConfig.fromEnv(DatabaseRole.READ_WRITE, env = env)
        assertEquals("svc_rw", readWrite.username)
        assertEquals("rw_secret_456", readWrite.password)

        val migrator = DatabaseConfig.fromEnv(DatabaseRole.MIGRATOR, env = env)
        assertEquals("svc_mig", migrator.username)
        assertEquals("mig_secret_789", migrator.password)
    }

    @Test
    fun `resolves defaults and supports parameterized prefix and role key via lambda lookup`() {
        val defaults = DatabaseConfig.fromEnv(DatabaseRole.READ_ONLY, env = emptyMap())
        assertEquals(
            "jdbc:postgresql://127.0.0.1:5432/app?reWriteBatchedInserts=true",
            defaults.jdbcUrl,
        )
        assertEquals("db_read_only", defaults.username)
        assertEquals("dev_read_only_change_me", defaults.password)
        assertEquals(DatabaseConfig.DEFAULT_MAX_POOL_SIZE, defaults.maximumPoolSize)
        assertEquals(DatabaseConfig.DEFAULT_CONNECTION_TIMEOUT_MS, defaults.connectionTimeoutMs)

        val customEnv = mapOf(
            "CUSTOM_PUBLIC_USER" to "custom_reader",
            "CUSTOM_PUBLIC_PASSWORD" to "custom_reader_pw",
            "CUSTOM_MAX_POOL_SIZE" to "16",
            "CUSTOM_CONNECTION_TIMEOUT_MS" to "3200",
        )
        val customConfig = DatabaseConfig.fromEnv(
            role = DatabaseRole.READ_ONLY,
            prefix = "custom",
            defaultDatabase = "custom_db",
            roleEnvKey = "public",
        ) { key -> customEnv[key] }

        assertEquals(
            "jdbc:postgresql://127.0.0.1:5432/custom_db?reWriteBatchedInserts=true",
            customConfig.jdbcUrl,
        )
        assertEquals("custom_reader", customConfig.username)
        assertEquals("custom_reader_pw", customConfig.password)
        assertEquals(16, customConfig.maximumPoolSize)
        assertEquals(3200L, customConfig.connectionTimeoutMs)
    }

    @Test
    fun `prefers explicit JDBC_DATABASE_URL when set`() {
        val env = mapOf(
            DatabaseConfig.ENV_POSTGRES_HOST to "ignored-host",
            DatabaseConfig.ENV_JDBC_DATABASE_URL to "jdbc:postgresql://primary.cluster:5439/main?sslmode=require",
        )

        val config = DatabaseConfig.fromEnv(DatabaseRole.READ_WRITE, env = env)
        assertEquals(
            "jdbc:postgresql://primary.cluster:5439/main?sslmode=require",
            config.jdbcUrl,
        )
    }

    @Test
    fun `rejects out of range and non-numeric port values`() {
        listOf("0", "-1", "65536", "99999", "not-a-port").forEach { invalidPort ->
            val env = mapOf(DatabaseConfig.ENV_POSTGRES_PORT to invalidPort)
            assertFailsWith<IllegalArgumentException> {
                DatabaseConfig.fromEnv(DatabaseRole.READ_ONLY, env = env)
            }
        }
    }

    @Test
    fun `rejects invalid pool parameters blank inputs and unsupported isolation level`() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig.fromEnv(
                role = DatabaseRole.READ_ONLY,
                env = mapOf(DatabaseConfig.ENV_DB_MAX_POOL_SIZE to "0"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig.fromEnv(
                role = DatabaseRole.READ_ONLY,
                env = mapOf(DatabaseConfig.ENV_DB_CONNECTION_TIMEOUT_MS to "abc"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig(jdbcUrl = "  ", username = "u", password = "p")
        }
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig(
                jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/app",
                username = "u",
                password = "p",
                maximumPoolSize = 4,
                minimumIdle = 5,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig(
                jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/app",
                username = "u",
                password = "p",
                transactionIsolationLevel = UNSUPPORTED_ISOLATION_LEVEL,
            )
        }
    }

    @Test
    fun `redacts password from diagnostic toString output`() {
        val secret = "super-sensitive-credential-991"
        val config = DatabaseConfig(
            jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/app",
            username = "svc_user",
            password = secret,
        )

        val rendered = config.toString()
        assertFalse(rendered.contains(secret))
        assertTrue(rendered.contains("password=***"))
        assertTrue(rendered.contains("username=svc_user"))
    }

    private companion object {
        private const val UNSUPPORTED_ISOLATION_LEVEL: Int = 999
    }
}
