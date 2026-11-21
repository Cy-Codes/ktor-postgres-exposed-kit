package codes.cy.kit.db

/**
 * Immutable Flyway schema readiness snapshot returned by [DatabaseFactory.checkSchemaReady] and
 * [ManagedDatabase.checkSchemaReady].
 *
 * @property ready `true` when `flyway_schema_history` is accessible and records at least the
 *   required number of successful migrations.
 * @property appliedMigrationCount Total number of successful migrations recorded in
 *   `flyway_schema_history` (`0` when uninitialized or unreachable).
 */
public data class SchemaReadinessStatus(
    public val ready: Boolean,
    public val appliedMigrationCount: Int,
)
