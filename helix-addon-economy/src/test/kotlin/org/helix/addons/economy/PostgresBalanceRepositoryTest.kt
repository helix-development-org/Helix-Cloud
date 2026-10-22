package org.helix.addons.economy

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.AfterTest

/**
 * Runs the repository contract against a real PostgreSQL when
 * `HELIX_TEST_POSTGRES_URL` (a JDBC url with user and password) is set;
 * skipped otherwise.
 */
class PostgresBalanceRepositoryTest : BalanceRepositoryContract() {
    private var repository: PostgresBalanceRepository? = null

    override fun create(): BalanceRepository {
        val url = System.getenv("HELIX_TEST_POSTGRES_URL")
        assumeTrue(!url.isNullOrBlank(), "HELIX_TEST_POSTGRES_URL not set")
        val source = PGSimpleDataSource().apply { setURL(url) }
        source.connection.use { it.createStatement().use { st -> st.executeUpdate("DROP SCHEMA IF EXISTS economy CASCADE") } }
        return PostgresBalanceRepository(source, 1000, clock).also { repository = it }
    }

    @AfterTest
    fun close() {
        repository?.close()
    }
}
