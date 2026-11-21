# ktor-postgres-exposed-kit

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Domain-agnostic PostgreSQL connection pooling (`HikariCP`), Exposed 1.0 `Dispatchers.IO` transaction bridging, `Statement.NO_GENERATED_KEYS` least-privilege insert execution, schema readiness checks, and advisory-locked Flyway migrations for Ktor JVM backends.

Repository: <https://github.com/cy-codes/ktor-postgres-exposed-kit>

---

## Maven Coordinates

Published under group `codes.cy.kit` and version `0.1.0` with `kotlin.explicitApi: strict` enabled:

| Module | Coordinates | Target | Purpose |
| :--- | :--- | :--- | :--- |
| **`ktor-postgres-exposed-kit`** | `codes.cy.kit:ktor-postgres-exposed-kit:0.1.0` | `jvm` | `DatabaseRole`, `DatabaseConfig`, `DatabaseFactory`, `ManagedDatabase`, `SchemaReadinessStatus`, `JdbcTransaction.executeUpdateNoReturning`, `FlywayMigrator`, `FlywayMigrationOptions` |

### Consuming via Amper (`libs.versions.toml` + `module.yaml`)

```toml
[libraries]
ktor-postgres-exposed-kit = { module = "codes.cy.kit:ktor-postgres-exposed-kit", version = "0.1.0" }
```

```yaml
repositories:
  - id: mavenLocal
    url: mavenLocal
    resolve: true

dependencies:
  - $libs.ktor.postgres.exposed.kit
```

Downstream modules transitively receive `exposed-core`, `exposed-jdbc`, `HikariCP`, and `flyway-core` via `: exported` declarations in `module.yaml`.

---

## Public API Overview (`codes.cy.kit.db`)

- **`DatabaseRole`**: Least-privilege PostgreSQL connection role enum (`READ_ONLY`, `READ_WRITE`, `MIGRATOR`).
- **`DatabaseConfig`**: Immutable `data class` with fail-fast validation, password-redacted `toString()`, and `DatabaseConfig.fromEnv(...)` overloads accepting `Map<String, String>` or `(String) -> String?`. Resolves `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `JDBC_DATABASE_URL`, `<PREFIX>_<ROLE>_USER`, `<PREFIX>_<ROLE>_PASSWORD`, `<PREFIX>_MAX_POOL_SIZE` / `DB_MAX_POOL_SIZE`, and `<PREFIX>_CONNECTION_TIMEOUT_MS` / `DB_CONNECTION_TIMEOUT_MS` (default `prefix = "DB"`, `defaultDatabase = "app"`).
- **`SchemaReadinessStatus`**: Immutable readiness result (`ready: Boolean`, `appliedMigrationCount: Int`).
- **`DatabaseFactory` & `ManagedDatabase`**:
  - `DatabaseFactory.createDataSource(config)`: Creates a validated `HikariDataSource` with `isAutoCommit = false` and `Connection.TRANSACTION_REPEATABLE_READ` (`"TRANSACTION_REPEATABLE_READ"`) by default.
  - `DatabaseFactory.connect(config, ioContext = Dispatchers.IO)` / `connect(dataSource, ioContext = Dispatchers.IO)`: Returns an `AutoCloseable` `ManagedDatabase` holding both `dataSource` and Exposed 1.0 `Database`.
  - `dbQuery { ... }` (on `ManagedDatabase` or `DatabaseFactory.dbQuery(database)`): Runs Exposed 1.0 `suspendTransaction(db = database)` inside `withContext(ioContext)` (`Dispatchers.IO` by default).
  - `executeUpdateNoReturning(sql) { stmt -> ... }` (on `ManagedDatabase`, `DatabaseFactory`, and as a `JdbcTransaction.executeUpdateNoReturning` extension): Prepares statements with `Statement.NO_GENERATED_KEYS` so PostgreSQL JDBC does not emit implicit `RETURNING *`.
  - `checkSchemaReady(schema = null, minimumAppliedMigrations = 1)` (on `ManagedDatabase` or `DatabaseFactory`): Queries `flyway_schema_history` (validating explicit schema identifiers against `^[A-Za-z_][A-Za-z0-9_]*$`), propagating coroutine `CancellationException` while mapping SQL/connectivity failures to `SchemaReadinessStatus(ready = false, appliedMigrationCount = 0)`.
- **`FlywayMigrator` & `FlywayMigrationOptions`**: Advisory-locked Flyway classpath migrator (`migrate(config, ...)` / `migrate(dataSource, ...)`) defaulting to `classpath:db/migrations`, `flyway.postgresql.transactional.lock = true`, `lockRetryCount = 50`, `validateOnMigrate = true`, `baselineOnMigrate = false`, and `cleanDisabled = true`.

---

## Usage Snippet

```kotlin
import codes.cy.kit.db.DatabaseConfig
import codes.cy.kit.db.DatabaseFactory
import codes.cy.kit.db.DatabaseRole
import codes.cy.kit.db.FlywayMigrator
import codes.cy.kit.db.executeUpdateNoReturning
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.selectAll

object ItemsTable : Table("app.items") {
    val slug = text("slug")
    val title = text("title")
}

suspend fun runDatabaseWorkflow() {
    // One-shot migration job / test setup using MIGRATOR role
    val migratorConfig = DatabaseConfig.fromEnv(DatabaseRole.MIGRATOR, prefix = "MYAPP", defaultDatabase = "myapp")
    FlywayMigrator.migrate(
        config = migratorConfig,
        locations = listOf("classpath:db/migrations"),
        schemas = listOf("app", "telemetry"),
        defaultSchema = "app",
    )

    // Runtime service using least-privilege READ_ONLY + append-only INSERT role
    val runtimeConfig = DatabaseConfig.fromEnv(DatabaseRole.READ_ONLY, prefix = "MYAPP", defaultDatabase = "myapp")
    DatabaseFactory.connect(runtimeConfig).use { db ->
        val readiness = db.checkSchemaReady(schema = "app")
        check(readiness.ready) { "Database migrations not applied yet." }

        val titles = db.dbQuery {
            ItemsTable.selectAll().map { row -> row[ItemsTable.title] }
        }

        // Works on tables where role holds INSERT without SELECT privilege
        db.executeUpdateNoReturning(
            "INSERT INTO telemetry.events (event_name, path) VALUES (?, ?)",
        ) { stmt ->
            stmt.setString(1, "PAGE_VIEW")
            stmt.setString(2, "/items/${titles.firstOrNull().orEmpty()}")
        }
    }
}
```

---

## Preserved Production & Toolchain Invariants

1. **PgJDBC `Statement.NO_GENERATED_KEYS` (Invariant 7)**: `executeUpdateNoReturning` explicitly passes `Statement.NO_GENERATED_KEYS` when preparing DML statements (both standalone and inside Exposed `JdbcTransaction`), preventing PgJDBC's implicit `RETURNING *` from failing with SQLState `42501` on `INSERT`-only least-privilege telemetry tables.
2. **HikariCP Pool Isolation & Non-Autocommit**: `DatabaseFactory.createDataSource` enforces `isAutoCommit = false` and `Connection.TRANSACTION_REPEATABLE_READ` (`"TRANSACTION_REPEATABLE_READ"`) by default, with `const val` pool size and timeout bounds.
3. **Non-Blocking `Dispatchers.IO` Transaction Bridge**: `dbQuery` dispatches Exposed 1.0 `suspendTransaction(db = database)` onto `Dispatchers.IO` (or injected `CoroutineContext`) so Ktor event-loop threads never block on JDBC I/O.
4. **Advisory-Locked Flyway Snapshot Safety**: `FlywayMigrator.migrate(dataSource, ...)` temporarily normalizes borrowed pool connections to `Connection.TRANSACTION_READ_COMMITTED` (restoring prior isolation on close) so concurrent Flyway runners sharing a `REPEATABLE READ` pool see migrations committed by earlier advisory-lock holders.
5. **Kotlin Toolchain `: exported` Propagation (Invariant 4)**: `exposed-core`, `exposed-jdbc`, `hikaricp`, and `flyway-core` are marked `: exported` in `module.yaml` so downstream consumers access `Database`, `JdbcTransaction`, `HikariDataSource`, and `MigrateResult` transitively.
6. **Strict Explicit API & Zero Domain Leakage**: Compiled with `kotlin.explicitApi: strict`, full KDoc on all public symbols, redacted `DatabaseConfig.toString()`, and zero application-specific nouns.

---

## Build, Test & Publish

This project uses Kotlin Toolchain (`0.13.0` / `./kotlin`):

```bash
# Compile library and test sources
./kotlin build

# Run unit tests and PostgreSQL 16 Testcontainers integration tests (requires Docker)
./kotlin test

# Publish artifact, sources, javadocs, and POM/module metadata to ~/.m2/repository
./kotlin publish mavenLocal
```

### Verification Results

- `./kotlin build`: `Build successful` (`ktor-postgres-exposed-kit` JVM main and test compilation succeeded with zero warnings).
- `./kotlin test`: **15 passed, 0 failed**:
  - `DatabaseConfigTest`: 6 unit tests passed (role credentials, parameterized prefix/role keys, `JDBC_DATABASE_URL` precedence, port validation, pool/isolation validation, password redaction in `toString`).
  - `FlywayMigrationOptionsTest`: 2 unit tests passed (default advisory-lock/immutability options and invalid option rejection).
  - `DatabaseFactoryValidationTest`: 3 unit tests passed (SQL identifier injection rejection, non-positive migration threshold rejection, blank SQL fail-fast before connection acquisition, unready status fallback).
  - `PostgresExposedIntegrationTest` (`postgres:16.8-alpine` via Testcontainers): 4 integration tests passed:
    - `checkSchemaReady` false before migration / true after migration / threshold check.
    - Concurrent 4-coroutine `FlywayMigrator.migrate(dataSource, ...)` serialized cleanly via PostgreSQL advisory locking (`1` migration executed, `1` row inserted).
    - `dbQuery` offloaded onto `Dispatchers.IO`, `autoCommit == false`, and `Connection.TRANSACTION_REPEATABLE_READ` (`"repeatable read"`).
    - `executeUpdateNoReturning` (standalone & inside Exposed `JdbcTransaction`) succeeded for an `INSERT`-only PostgreSQL role without `SELECT` privilege, while `Statement.RETURN_GENERATED_KEYS` failed with SQLState `42501` (`insufficient_privilege`).
- `./kotlin publish mavenLocal`: `Publication successful` — published `codes.cy.kit:ktor-postgres-exposed-kit:0.1.0` (`.jar`, `-sources.jar`, `-javadoc.jar`, `.pom`, `.module`) to `~/.m2/repository/codes/cy/kit/ktor-postgres-exposed-kit/0.1.0/` with `<url>https://github.com/cy-codes/ktor-postgres-exposed-kit</url>`.
