package codes.cy.kit.db

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.flywaydb.core.api.output.MigrateResult

/**
 * Immutable Flyway migration options used by [FlywayMigrator].
 *
 * Defaults to classpath SQL migrations at `classpath:db/migrations`, PostgreSQL transactional
 * advisory locking (`flyway.postgresql.transactional.lock = true`), `baselineOnMigrate = false`,
 * `validateOnMigrate = true`, and `cleanDisabled = true` to enforce immutable applied deltas.
 *
 * @property locations Flyway migration scan locations (classpath or filesystem).
 * @property schemas Managed PostgreSQL schemas created and tracked by Flyway. When empty, Flyway
 *   uses the connection's default schema.
 * @property defaultSchema Schema holding `flyway_schema_history`. Defaults to the first element of
 *   [schemas] when non-empty.
 * @property createSchemas Whether Flyway creates missing [schemas] automatically.
 * @property baselineOnMigrate Whether to baseline an existing non-empty schema automatically.
 * @property validateOnMigrate Whether to validate checksums of previously applied migrations.
 * @property cleanDisabled Guard preventing accidental destructive `flyway clean` calls.
 * @property lockRetryCount Number of retries waiting on PostgreSQL advisory migration locks.
 * @property transactionalLock Configures `flyway.postgresql.transactional.lock` for PostgreSQL
 *   advisory locking during migration execution.
 */
public data class FlywayMigrationOptions(
    public val locations: List<String> = listOf(FlywayMigrator.DEFAULT_MIGRATIONS_LOCATION),
    public val schemas: List<String> = emptyList(),
    public val defaultSchema: String? = schemas.firstOrNull(),
    public val createSchemas: Boolean = true,
    public val baselineOnMigrate: Boolean = false,
    public val validateOnMigrate: Boolean = true,
    public val cleanDisabled: Boolean = true,
    public val lockRetryCount: Int = FlywayMigrator.DEFAULT_LOCK_RETRY_COUNT,
    public val transactionalLock: Boolean = true,
) {
    init {
        require(locations.isNotEmpty() && locations.all { it.isNotBlank() }) {
            "Flyway locations must contain at least one non-blank location."
        }
        require(schemas.all { it.isNotBlank() }) {
            "Flyway schemas must not contain blank schema names."
        }
        defaultSchema?.let { schema ->
            require(schema.isNotBlank()) { "defaultSchema must not be blank when provided." }
        }
        require(lockRetryCount >= 0) {
            "lockRetryCount must be non-negative, got $lockRetryCount."
        }
    }
}

/**
 * Advisory-locked Flyway classpath migrator helper for one-shot migration runners and test
 * harnesses.
 *
 * Never intended for routine application server startup; keep DDL credentials isolated to
 * [DatabaseRole.MIGRATOR] or explicit migration jobs.
 */
public object FlywayMigrator {
    /** Default classpath location scanned for Flyway SQL migrations. */
    public const val DEFAULT_MIGRATIONS_LOCATION: String = "classpath:db/migrations"

    /** Default PostgreSQL advisory lock retry count. */
    public const val DEFAULT_LOCK_RETRY_COUNT: Int = 50

    /** Flyway PostgreSQL configuration key controlling transactional advisory locking. */
    public const val PG_TRANSACTIONAL_LOCK_PROPERTY: String = "flyway.postgresql.transactional.lock"

    /**
     * Side-effecting migration command running Flyway against [config] using PostgreSQL advisory
     * locking.
     */
    public fun migrate(
        config: DatabaseConfig,
        locations: List<String> = listOf(DEFAULT_MIGRATIONS_LOCATION),
        schemas: List<String> = emptyList(),
        defaultSchema: String? = schemas.firstOrNull(),
        createSchemas: Boolean = true,
        baselineOnMigrate: Boolean = false,
        validateOnMigrate: Boolean = true,
        cleanDisabled: Boolean = true,
        lockRetryCount: Int = DEFAULT_LOCK_RETRY_COUNT,
        transactionalLock: Boolean = true,
    ): MigrateResult =
        migrate(
            config = config,
            options = FlywayMigrationOptions(
                locations = locations,
                schemas = schemas,
                defaultSchema = defaultSchema,
                createSchemas = createSchemas,
                baselineOnMigrate = baselineOnMigrate,
                validateOnMigrate = validateOnMigrate,
                cleanDisabled = cleanDisabled,
                lockRetryCount = lockRetryCount,
                transactionalLock = transactionalLock,
            ),
        )

    /**
     * Side-effecting migration command running Flyway against [config] with explicit [options].
     */
    public fun migrate(
        config: DatabaseConfig,
        options: FlywayMigrationOptions,
    ): MigrateResult =
        configureAndMigrate(options) {
            dataSource(config.jdbcUrl, config.username, config.password)
        }

    /**
     * Side-effecting migration command running Flyway against an existing [dataSource] using
     * PostgreSQL advisory locking.
     */
    public fun migrate(
        dataSource: DataSource,
        locations: List<String> = listOf(DEFAULT_MIGRATIONS_LOCATION),
        schemas: List<String> = emptyList(),
        defaultSchema: String? = schemas.firstOrNull(),
        createSchemas: Boolean = true,
        baselineOnMigrate: Boolean = false,
        validateOnMigrate: Boolean = true,
        cleanDisabled: Boolean = true,
        lockRetryCount: Int = DEFAULT_LOCK_RETRY_COUNT,
        transactionalLock: Boolean = true,
    ): MigrateResult =
        migrate(
            dataSource = dataSource,
            options = FlywayMigrationOptions(
                locations = locations,
                schemas = schemas,
                defaultSchema = defaultSchema,
                createSchemas = createSchemas,
                baselineOnMigrate = baselineOnMigrate,
                validateOnMigrate = validateOnMigrate,
                cleanDisabled = cleanDisabled,
                lockRetryCount = lockRetryCount,
                transactionalLock = transactionalLock,
            ),
        )

    /**
     * Side-effecting migration command running Flyway against [dataSource] with explicit [options].
     *
     * Wraps [dataSource] so Flyway borrows connections at [Connection.TRANSACTION_READ_COMMITTED]
     * (restoring the prior isolation level on close), preventing `REPEATABLE READ` pool snapshots
     * from masking commits made by an earlier advisory-lock holder.
     */
    public fun migrate(
        dataSource: DataSource,
        options: FlywayMigrationOptions,
    ): MigrateResult =
        configureAndMigrate(options) {
            dataSource(ReadCommittedFlywayDataSource(dataSource))
        }

    private fun configureAndMigrate(
        options: FlywayMigrationOptions,
        configureSource: FluentConfiguration.() -> FluentConfiguration,
    ): MigrateResult =
        Flyway.configure()
            .configureSource()
            .apply {
                if (options.schemas.isNotEmpty()) {
                    schemas(*options.schemas.toTypedArray())
                }
                options.defaultSchema?.let { schema ->
                    defaultSchema(schema)
                }
            }
            .createSchemas(options.createSchemas)
            .locations(*options.locations.toTypedArray())
            .baselineOnMigrate(options.baselineOnMigrate)
            .validateOnMigrate(options.validateOnMigrate)
            .cleanDisabled(options.cleanDisabled)
            .lockRetryCount(options.lockRetryCount)
            .configuration(
                mapOf(
                    PG_TRANSACTIONAL_LOCK_PROPERTY to options.transactionalLock.toString(),
                ),
            )
            .load()
            .migrate()

    private class ReadCommittedFlywayDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        override fun getConnection(): Connection =
            wrapReadCommitted(delegate.connection)

        override fun getConnection(username: String?, password: String?): Connection =
            wrapReadCommitted(delegate.getConnection(username, password))

        private fun wrapReadCommitted(raw: Connection): Connection {
            val previousIsolation = raw.transactionIsolation
            if (previousIsolation == Connection.TRANSACTION_READ_COMMITTED) {
                return raw
            }
            raw.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, args ->
                if (method.name == CLOSE_METHOD_NAME && (args == null || args.isEmpty())) {
                    runCatching {
                        if (!raw.isClosed) {
                            raw.transactionIsolation = previousIsolation
                        }
                    }
                    raw.close()
                    null
                } else {
                    try {
                        if (args == null) method.invoke(raw) else method.invoke(raw, *args)
                    } catch (ex: InvocationTargetException) {
                        throw ex.targetException
                    }
                }
            } as Connection
        }

        private companion object {
            private const val CLOSE_METHOD_NAME: String = "close"
        }
    }
}
