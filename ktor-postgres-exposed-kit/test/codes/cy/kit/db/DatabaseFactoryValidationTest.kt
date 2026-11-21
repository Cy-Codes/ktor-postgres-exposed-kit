package codes.cy.kit.db

import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class DatabaseFactoryValidationTest {
    @Test
    fun `checkSchemaReady rejects unsafe SQL schema identifiers and non-positive migration counts`() = runTest {
        val dataSource = StubFailingDataSource()

        listOf("", "app; DROP TABLE items", "1leading_digit", "schema-dash").forEach { unsafeSchema ->
            assertFailsWith<IllegalArgumentException> {
                DatabaseFactory.checkSchemaReady(dataSource = dataSource, schema = unsafeSchema)
            }
        }

        listOf(0, -1).forEach { invalidCount ->
            assertFailsWith<IllegalArgumentException> {
                DatabaseFactory.checkSchemaReady(
                    dataSource = dataSource,
                    minimumAppliedMigrations = invalidCount,
                )
            }
        }
    }

    @Test
    fun `checkSchemaReady returns unready status when connection fails`() = runTest {
        val status = DatabaseFactory.checkSchemaReady(
            dataSource = StubFailingDataSource(),
            schema = "app_kit",
        )

        assertFalse(status.ready)
        assertEquals(0, status.appliedMigrationCount)
    }

    @Test
    fun `executeUpdateNoReturning rejects blank SQL before acquiring a connection`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            DatabaseFactory.executeUpdateNoReturning(
                dataSource = StubFailingDataSource(),
                sql = "   ",
            )
        }
    }

    private class StubFailingDataSource : DataSource {
        override fun getConnection(): Connection = throw SQLException("Stub connection failure")
        override fun getConnection(username: String?, password: String?): Connection = getConnection()
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }
}
