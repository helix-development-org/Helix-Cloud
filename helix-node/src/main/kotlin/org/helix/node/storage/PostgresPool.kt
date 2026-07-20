package org.helix.node.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.helix.node.config.NodeConfig

/**
 * Builds the shared PostgreSQL connection pool used by both addon storage
 * and the audit log, so the node opens exactly one pool.
 */
object PostgresPool {
    /**
     * Creates a pooled data source from the storage settings.
     *
     * @param settings database connection settings.
     * @return a configured Hikari data source.
     */
    fun create(settings: NodeConfig.StorageSettings): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = settings.url
                username = settings.user
                password = settings.password
                maximumPoolSize = settings.poolSize
                poolName = "helix-postgres"
            },
        )
}
