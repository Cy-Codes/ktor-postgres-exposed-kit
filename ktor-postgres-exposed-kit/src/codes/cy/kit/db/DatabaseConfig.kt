package codes.cy.kit.db

import java.sql.Connection

/**
 * Immutable PostgreSQL & HikariCP connection pool configuration.
 *
 * Construct directly for explicit/container configurations or call [fromEnv] to resolve
 * role-scoped credentials and pool parameters from environment variables.
 *
 * @property jdbcUrl JDBC connection string targeting PostgreSQL.
 * @property username Database role username.
 * @property password Database role password (redacted from [toString]).
 * @property maximumPoolSize Maximum number of pooled JDBC connections (must be positive).
 * @property minimumIdle Optional minimum idle connections retained by HikariCP (`0..maximumPoolSize`).
 * @property connectionTimeoutMs Connection acquisition timeout in milliseconds (must be positive).
 * @property idleTimeoutMs Maximum idle lifetime before pool retirement in milliseconds.
 * @property maxLifetimeMs Maximum connection lifetime in milliseconds.
 * @property transactionIsolationLevel JDBC isolation constant from [Connection] (defaults to
 *   [Connection.TRANSACTION_REPEATABLE_READ]).
 * @property driverClassName Fully qualified JDBC driver class name.
 * @property poolName Optional diagnostic pool name registered with HikariCP.
 */
public data class DatabaseConfig(
    public val jdbcUrl: String,
    public val username: String,
    public val password: String,
    public val maximumPoolSize: Int = DEFAULT_MAX_POOL_SIZE,
    public val minimumIdle: Int? = null,
    public val connectionTimeoutMs: Long = DEFAULT_CONNECTION_TIMEOUT_MS,
    public val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    public val maxLifetimeMs: Long = DEFAULT_MAX_LIFETIME_MS,
    public val transactionIsolationLevel: Int = DEFAULT_TRANSACTION_ISOLATION_LEVEL,
    public val driverClassName: String = DEFAULT_DRIVER_CLASS,
    public val poolName: String? = null,
) {
    init {
        require(jdbcUrl.isNotBlank()) { "jdbcUrl must not be blank." }
        require(username.isNotBlank()) { "username must not be blank." }
        require(maximumPoolSize > 0) {
            "maximumPoolSize must be positive, got $maximumPoolSize."
        }
        minimumIdle?.let { idle ->
            require(idle in MIN_IDLE_FLOOR..maximumPoolSize) {
                "minimumIdle ($idle) must be within $MIN_IDLE_FLOOR..$maximumPoolSize."
            }
        }
        require(connectionTimeoutMs > 0L) {
            "connectionTimeoutMs must be positive, got $connectionTimeoutMs."
        }
        require(idleTimeoutMs >= 0L) {
            "idleTimeoutMs must be non-negative, got $idleTimeoutMs."
        }
        require(maxLifetimeMs >= 0L) {
            "maxLifetimeMs must be non-negative, got $maxLifetimeMs."
        }
        require(driverClassName.isNotBlank()) { "driverClassName must not be blank." }
        resolveTransactionIsolationName(transactionIsolationLevel)
    }

    /**
     * Pure query returning a diagnostic string representation with [password] redacted.
     */
    override fun toString(): String =
        "DatabaseConfig(" +
            "jdbcUrl=$jdbcUrl, " +
            "username=$username, " +
            "password=$REDACTED_PASSWORD, " +
            "maximumPoolSize=$maximumPoolSize, " +
            "minimumIdle=$minimumIdle, " +
            "connectionTimeoutMs=$connectionTimeoutMs, " +
            "idleTimeoutMs=$idleTimeoutMs, " +
            "maxLifetimeMs=$maxLifetimeMs, " +
            "transactionIsolationLevel=$transactionIsolationLevel, " +
            "driverClassName=$driverClassName, " +
            "poolName=$poolName)"

    public companion object {
        /** Default PostgreSQL host when `POSTGRES_HOST` is unset or blank. */
        public const val DEFAULT_HOST: String = "127.0.0.1"

        /** Default PostgreSQL port when `POSTGRES_PORT` is unset or blank. */
        public const val DEFAULT_PORT: Int = 5432

        /** Default PostgreSQL database name when `POSTGRES_DB` is unset or blank. */
        public const val DEFAULT_DATABASE: String = "app"

        /** Default environment variable prefix used for role credential resolution. */
        public const val DEFAULT_ENV_PREFIX: String = "DB"

        /** Default HikariCP maximum pool size. */
        public const val DEFAULT_MAX_POOL_SIZE: Int = 10

        /** Default HikariCP connection timeout in milliseconds. */
        public const val DEFAULT_CONNECTION_TIMEOUT_MS: Long = 10_000L

        /** Default HikariCP idle timeout in milliseconds. */
        public const val DEFAULT_IDLE_TIMEOUT_MS: Long = 600_000L

        /** Default HikariCP max lifetime in milliseconds. */
        public const val DEFAULT_MAX_LIFETIME_MS: Long = 1_800_000L

        /** Default PostgreSQL JDBC driver class name. */
        public const val DEFAULT_DRIVER_CLASS: String = "org.postgresql.Driver"

        /** Canonical HikariCP isolation name paired with [DEFAULT_TRANSACTION_ISOLATION_LEVEL]. */
        public const val DEFAULT_TRANSACTION_ISOLATION: String = "TRANSACTION_REPEATABLE_READ"

        /** Default JDBC transaction isolation level (`REPEATABLE READ`). */
        public const val DEFAULT_TRANSACTION_ISOLATION_LEVEL: Int = Connection.TRANSACTION_REPEATABLE_READ

        /** Environment variable key for PostgreSQL hostname. */
        public const val ENV_POSTGRES_HOST: String = "POSTGRES_HOST"

        /** Environment variable key for PostgreSQL port. */
        public const val ENV_POSTGRES_PORT: String = "POSTGRES_PORT"

        /** Environment variable key for PostgreSQL database name. */
        public const val ENV_POSTGRES_DB: String = "POSTGRES_DB"

        /** Environment variable key overriding the full JDBC connection URL. */
        public const val ENV_JDBC_DATABASE_URL: String = "JDBC_DATABASE_URL"

        /** Environment variable key for maximum HikariCP pool size. */
        public const val ENV_DB_MAX_POOL_SIZE: String = "DB_MAX_POOL_SIZE"

        /** Environment variable key for HikariCP connection timeout in milliseconds. */
        public const val ENV_DB_CONNECTION_TIMEOUT_MS: String = "DB_CONNECTION_TIMEOUT_MS"

        private const val MIN_PORT: Int = 1
        private const val MAX_PORT: Int = 65535
        private const val MIN_IDLE_FLOOR: Int = 0
        private const val REWRITE_BATCHED_INSERTS_QUERY: String = "reWriteBatchedInserts=true"
        private const val DEFAULT_PASSWORD_PREFIX: String = "dev_"
        private const val DEFAULT_PASSWORD_SUFFIX: String = "_change_me"
        private const val REDACTED_PASSWORD: String = "***"

        private const val ISOLATION_NONE_NAME: String = "TRANSACTION_NONE"
        private const val ISOLATION_READ_UNCOMMITTED_NAME: String = "TRANSACTION_READ_UNCOMMITTED"
        private const val ISOLATION_READ_COMMITTED_NAME: String = "TRANSACTION_READ_COMMITTED"
        private const val ISOLATION_SERIALIZABLE_NAME: String = "TRANSACTION_SERIALIZABLE"

        /**
         * Pure query resolving a role-scoped [DatabaseConfig] from an environment map.
         *
         * Looks up `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `JDBC_DATABASE_URL`,
         * `<PREFIX>_<ROLE>_USER`, `<PREFIX>_<ROLE>_PASSWORD`, `<PREFIX>_MAX_POOL_SIZE` /
         * `DB_MAX_POOL_SIZE`, and `<PREFIX>_CONNECTION_TIMEOUT_MS` / `DB_CONNECTION_TIMEOUT_MS`.
         *
         * @param role Target least-privilege [DatabaseRole].
         * @param env Environment variable map (defaults to [System.getenv]).
         * @param prefix Uppercase/alphanumeric environment key prefix (defaults to `"DB"`).
         * @param defaultDatabase Fallback database name when `POSTGRES_DB` is absent.
         * @param roleEnvKey Role key segment between [prefix] and `_USER`/`_PASSWORD`.
         * @return Validated immutable [DatabaseConfig].
         */
        public fun fromEnv(
            role: DatabaseRole,
            env: Map<String, String> = System.getenv(),
            prefix: String = DEFAULT_ENV_PREFIX,
            defaultDatabase: String = DEFAULT_DATABASE,
            roleEnvKey: String = role.defaultEnvSegment,
        ): DatabaseConfig =
            fromEnvLookup(
                prefix = prefix,
                defaultDatabase = defaultDatabase,
                roleEnvKey = roleEnvKey,
                envLookup = env::get,
            )

        /**
         * Pure query resolving a role-scoped [DatabaseConfig] via a custom [envLookup] function.
         *
         * @param role Target least-privilege [DatabaseRole].
         * @param prefix Uppercase/alphanumeric environment key prefix (defaults to `"DB"`).
         * @param defaultDatabase Fallback database name when `POSTGRES_DB` is absent.
         * @param roleEnvKey Role key segment between [prefix] and `_USER`/`_PASSWORD`.
         * @param envLookup Key-to-value lookup callback.
         * @return Validated immutable [DatabaseConfig].
         */
        public fun fromEnv(
            role: DatabaseRole,
            prefix: String = DEFAULT_ENV_PREFIX,
            defaultDatabase: String = DEFAULT_DATABASE,
            roleEnvKey: String = role.defaultEnvSegment,
            envLookup: (String) -> String?,
        ): DatabaseConfig =
            fromEnvLookup(
                prefix = prefix,
                defaultDatabase = defaultDatabase,
                roleEnvKey = roleEnvKey,
                envLookup = envLookup,
            )

        internal fun resolveTransactionIsolationName(level: Int): String =
            when (level) {
                Connection.TRANSACTION_NONE -> ISOLATION_NONE_NAME
                Connection.TRANSACTION_READ_UNCOMMITTED -> ISOLATION_READ_UNCOMMITTED_NAME
                Connection.TRANSACTION_READ_COMMITTED -> ISOLATION_READ_COMMITTED_NAME
                Connection.TRANSACTION_REPEATABLE_READ -> DEFAULT_TRANSACTION_ISOLATION
                Connection.TRANSACTION_SERIALIZABLE -> ISOLATION_SERIALIZABLE_NAME
                else -> throw IllegalArgumentException(
                    "Unsupported JDBC transaction isolation level: $level.",
                )
            }

        private fun fromEnvLookup(
            prefix: String,
            defaultDatabase: String,
            roleEnvKey: String,
            envLookup: (String) -> String?,
        ): DatabaseConfig {
            val normalizedPrefix = prefix.trim().uppercase()
            require(normalizedPrefix.isNotBlank()) { "Environment prefix must not be blank." }
            val normalizedRoleKey = roleEnvKey.trim().uppercase()
            require(normalizedRoleKey.isNotBlank()) { "Role environment key must not be blank." }
            val normalizedDefaultDb = defaultDatabase.trim()
            require(normalizedDefaultDb.isNotBlank()) { "defaultDatabase must not be blank." }

            val host = envLookup(ENV_POSTGRES_HOST)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST
            val port = parsePort(envLookup(ENV_POSTGRES_PORT))
            val database = envLookup(ENV_POSTGRES_DB)?.takeIf { it.isNotBlank() } ?: normalizedDefaultDb
            val jdbcUrl = envLookup(ENV_JDBC_DATABASE_URL)?.takeIf { it.isNotBlank() }
                ?: "jdbc:postgresql://$host:$port/$database?$REWRITE_BATCHED_INSERTS_QUERY"

            val roleSlug = normalizedRoleKey.lowercase()
            val userKey = "${normalizedPrefix}_${normalizedRoleKey}_USER"
            val passwordKey = "${normalizedPrefix}_${normalizedRoleKey}_PASSWORD"
            val defaultUser = "${normalizedPrefix.lowercase()}_$roleSlug"
            val defaultPassword = "$DEFAULT_PASSWORD_PREFIX$roleSlug$DEFAULT_PASSWORD_SUFFIX"

            val username = envLookup(userKey)?.takeIf { it.isNotBlank() } ?: defaultUser
            val password = envLookup(passwordKey)?.takeIf { it.isNotBlank() } ?: defaultPassword

            val poolSize = parsePositiveInt(
                raw = firstNonBlank(
                    envLookup("${normalizedPrefix}_MAX_POOL_SIZE"),
                    envLookup(ENV_DB_MAX_POOL_SIZE),
                ),
                keyName = ENV_DB_MAX_POOL_SIZE,
                fallback = DEFAULT_MAX_POOL_SIZE,
            )
            val connectionTimeoutMs = parsePositiveLong(
                raw = firstNonBlank(
                    envLookup("${normalizedPrefix}_CONNECTION_TIMEOUT_MS"),
                    envLookup(ENV_DB_CONNECTION_TIMEOUT_MS),
                ),
                keyName = ENV_DB_CONNECTION_TIMEOUT_MS,
                fallback = DEFAULT_CONNECTION_TIMEOUT_MS,
            )

            return DatabaseConfig(
                jdbcUrl = jdbcUrl,
                username = username,
                password = password,
                maximumPoolSize = poolSize,
                connectionTimeoutMs = connectionTimeoutMs,
            )
        }

        private fun firstNonBlank(primary: String?, secondary: String?): String? =
            primary?.takeIf { it.isNotBlank() } ?: secondary?.takeIf { it.isNotBlank() }

        private fun parsePort(rawPort: String?): Int {
            val trimmed = rawPort?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT_PORT
            val parsed = requireNotNull(trimmed.toIntOrNull()) {
                "Invalid $ENV_POSTGRES_PORT '$trimmed'. Expected integer in $MIN_PORT..$MAX_PORT."
            }
            require(parsed in MIN_PORT..MAX_PORT) {
                "Invalid $ENV_POSTGRES_PORT '$parsed'. Must be in $MIN_PORT..$MAX_PORT."
            }
            return parsed
        }

        private fun parsePositiveInt(raw: String?, keyName: String, fallback: Int): Int {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
            val parsed = requireNotNull(trimmed.toIntOrNull()) {
                "Invalid $keyName '$trimmed'. Expected a positive integer."
            }
            require(parsed > 0) { "Invalid $keyName '$parsed'. Must be positive." }
            return parsed
        }

        private fun parsePositiveLong(raw: String?, keyName: String, fallback: Long): Long {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
            val parsed = requireNotNull(trimmed.toLongOrNull()) {
                "Invalid $keyName '$trimmed'. Expected a positive long integer."
            }
            require(parsed > 0L) { "Invalid $keyName '$parsed'. Must be positive." }
            return parsed
        }
    }
}
