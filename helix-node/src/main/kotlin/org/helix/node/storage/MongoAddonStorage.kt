package org.helix.node.storage

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import org.helix.api.storage.AddonStorage

/**
 * [AddonStorage] backed by a shared MongoDB collection `addon_storage` — the
 * `mongodb` storage mode.
 *
 * Each document has a compound `_id` `{ a: addonId, k: docKey }` so an addon's
 * documents are isolated by [addonId], mirroring the postgres backend.
 *
 * @property database shared MongoDB database.
 * @property addonId owning addon id.
 */
class MongoAddonStorage(
    database: MongoDatabase,
    private val addonId: String,
) : AddonStorage {
    private val collection = database.getCollection(COLLECTION)

    private fun id(key: String): Document = Document("a", addonId).append("k", key)

    override fun read(key: String): String? =
        collection.find(Filters.eq("_id", id(key))).first()?.getString("value")

    override fun write(key: String, value: String) {
        collection.replaceOne(
            Filters.eq("_id", id(key)),
            Document("_id", id(key)).append("value", value),
            ReplaceOptions().upsert(true),
        )
    }

    override fun delete(key: String): Boolean =
        collection.deleteOne(Filters.eq("_id", id(key))).deletedCount > 0

    override fun keys(): List<String> =
        collection.find(Filters.eq("_id.a", addonId))
            .mapNotNull { it.get("_id", Document::class.java)?.getString("k") }

    private companion object {
        /** Shared collection holding every addon's documents. */
        const val COLLECTION = "addon_storage"
    }
}
