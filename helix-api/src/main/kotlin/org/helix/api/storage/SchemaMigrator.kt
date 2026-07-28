package org.helix.api.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Envelope wrapping a persisted document with a schema version, so a store
 * can evolve its on-disk/database shape over time through registered
 * [SchemaMigrator] migrations instead of a one-off ad-hoc migration written
 * from scratch each time (see `MessageBundle`'s legacy single-language
 * format migration, the precedent this generalizes).
 *
 * @property schemaVersion the document's schema version.
 * @property data the document body, in whatever JSON shape the store uses.
 */
@Serializable
data class VersionedDocument(
    val schemaVersion: Int,
    val data: JsonElement,
)

/**
 * Reads a [VersionedDocument] from an [AddonStorage] key, upgrading it to
 * [currentVersion] by applying [migrations] in order and persisting the
 * result when a migration ran. The sanctioned pattern for future addon-data
 * schema changes: bump [currentVersion], add the migration from the version
 * it replaces, done — [read] takes care of applying it once and writing the
 * upgraded form back.
 *
 * A document with no envelope (written before a store adopted this helper)
 * is treated as schema version 0, its raw body becoming [VersionedDocument.data]
 * unchanged — so an existing store can adopt this helper incrementally,
 * without migrating its already-persisted documents by hand.
 *
 * @property currentVersion the latest schema version this store understands.
 * @property migrations one migration per version, keyed by the version it
 *  upgrades *from*; each returns the body upgraded by exactly one version.
 */
class SchemaMigrator(
    private val currentVersion: Int,
    private val migrations: Map<Int, (JsonElement) -> JsonElement> = emptyMap(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads [key] from [storage], migrating it to [currentVersion] and
     * writing the upgraded document back if its persisted version was older.
     *
     * @param storage document store to read from (and write back to, if a
     *  migration ran).
     * @param key document key.
     * @return the up-to-date body, or `null` when the key is absent.
     */
    fun read(storage: AddonStorage, key: String): JsonElement? {
        val raw = storage.read(key) ?: return null
        var envelope = runCatching { json.decodeFromString(VersionedDocument.serializer(), raw) }
            .getOrElse { VersionedDocument(0, json.parseToJsonElement(raw)) }
        var migrated = false
        while (envelope.schemaVersion < currentVersion) {
            val migrate = migrations[envelope.schemaVersion] ?: break
            envelope = VersionedDocument(envelope.schemaVersion + 1, migrate(envelope.data))
            migrated = true
        }
        if (migrated) {
            persist(storage, key, envelope)
        }
        return envelope.data
    }

    /**
     * Writes [data] to [storage] under [key], wrapped at [currentVersion].
     *
     * @param storage document store to write to.
     * @param key document key.
     * @param data the document body.
     */
    fun write(storage: AddonStorage, key: String, data: JsonElement) {
        persist(storage, key, VersionedDocument(currentVersion, data))
    }

    private fun persist(storage: AddonStorage, key: String, envelope: VersionedDocument) {
        storage.write(key, json.encodeToString(VersionedDocument.serializer(), envelope))
    }
}
