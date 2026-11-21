package codes.cy.kit.db

/**
 * Least-privilege PostgreSQL connection role profile resolved by [DatabaseConfig.fromEnv].
 *
 * Separates read-only/telemetry-append workloads, read-write DML workloads, and schema migration
 * ownership across independent database credentials.
 */
public enum class DatabaseRole(
    internal val defaultEnvSegment: String,
) {
    /** Read-only query profile (optionally paired with `INSERT`-only append grants). */
    READ_ONLY(defaultEnvSegment = "READ_ONLY"),

    /** Read-write DML profile (`SELECT`, `INSERT`, `UPDATE`, `DELETE`) without DDL ownership. */
    READ_WRITE(defaultEnvSegment = "READ_WRITE"),

    /** Schema migration owner profile (`DDL` / Flyway execution only). */
    MIGRATOR(defaultEnvSegment = "MIGRATOR"),
}
