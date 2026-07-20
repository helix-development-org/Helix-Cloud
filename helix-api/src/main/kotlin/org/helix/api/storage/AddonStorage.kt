package org.helix.api.storage

/**
 * A document store scoped to a single addon.
 *
 * Each entry is a key mapped to a serialized value (typically JSON the
 * addon produces itself). The backend is chosen centrally by the node —
 * plain files or the shared PostgreSQL database — so an addon stores the
 * same way regardless of the network's storage mode.
 */
interface AddonStorage {
    /**
     * Reads a document.
     *
     * @param key document key.
     * @return the stored value, or `null` when absent.
     */
    fun read(key: String): String?

    /**
     * Writes (creates or replaces) a document.
     *
     * @param key document key.
     * @param value serialized value.
     */
    fun write(key: String, value: String)

    /**
     * Deletes a document.
     *
     * @param key document key.
     * @return `true` if the document existed.
     */
    fun delete(key: String): Boolean

    /**
     * Lists all document keys of this addon.
     *
     * @return the keys, order unspecified.
     */
    fun keys(): List<String>
}
