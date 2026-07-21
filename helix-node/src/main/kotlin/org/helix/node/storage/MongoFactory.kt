package org.helix.node.storage

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.helix.node.config.NodeConfig

/**
 * Builds the shared MongoDB client used by both addon storage and the audit
 * log, so the node opens exactly one connection pool.
 */
object MongoFactory {
    /**
     * Creates a pooled MongoDB client from the storage settings.
     *
     * @param settings storage connection settings (`url` is the MongoDB
     *  connection string, `poolSize` the maximum pool size).
     * @return a configured MongoDB client.
     */
    fun create(settings: NodeConfig.StorageSettings): MongoClient {
        val clientSettings = MongoClientSettings.builder()
            .applicationName("helix-node")
            .applyConnectionString(ConnectionString(settings.url))
            .applyToConnectionPoolSettings { it.maxSize(settings.poolSize) }
            .build()
        return MongoClients.create(clientSettings)
    }
}
