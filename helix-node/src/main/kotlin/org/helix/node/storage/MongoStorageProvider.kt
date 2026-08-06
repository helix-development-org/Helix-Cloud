package org.helix.node.storage

import com.mongodb.client.MongoDatabase
import org.helix.api.storage.AddonStorage
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * [StorageProvider] for the `mongodb` mode: all addons share one MongoDB
 * database and the `addon_storage` collection.
 *
 * The MongoDB client is owned externally (shared with the audit log) and is
 * not closed here.
 *
 * @property database shared MongoDB database.
 */
class MongoStorageProvider(private val database: MongoDatabase) : StorageProvider {
    init {
        LoggerFactory.getLogger(MongoStorageProvider::class.java).info("Addon storage using MongoDB")
    }

    override fun forAddon(addonId: String, dataDirectory: Path): AddonStorage =
        MongoAddonStorage(database, addonId)
}
