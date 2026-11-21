package codes.cy.kit.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlywayMigrationOptionsTest {
    @Test
    fun `uses immutable defaults aligned with advisory locking and delta safety`() {
        val options = FlywayMigrationOptions()

        assertEquals(listOf(FlywayMigrator.DEFAULT_MIGRATIONS_LOCATION), options.locations)
        assertTrue(options.schemas.isEmpty())
        assertTrue(options.createSchemas)
        assertFalse(options.baselineOnMigrate)
        assertTrue(options.validateOnMigrate)
        assertTrue(options.cleanDisabled)
        assertEquals(FlywayMigrator.DEFAULT_LOCK_RETRY_COUNT, options.lockRetryCount)
        assertTrue(options.transactionalLock)
    }

    @Test
    fun `rejects invalid locations blank schemas or negative lock retry count`() {
        assertFailsWith<IllegalArgumentException> {
            FlywayMigrationOptions(locations = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            FlywayMigrationOptions(locations = listOf("  "))
        }
        assertFailsWith<IllegalArgumentException> {
            FlywayMigrationOptions(schemas = listOf("valid_schema", " "))
        }
        assertFailsWith<IllegalArgumentException> {
            FlywayMigrationOptions(defaultSchema = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            FlywayMigrationOptions(lockRetryCount = -1)
        }
    }
}
