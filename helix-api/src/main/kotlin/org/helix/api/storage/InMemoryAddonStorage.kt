package org.helix.api.storage

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [AddonStorage], the default when no node persistence is wired
 * (for example in addon unit tests).
 */
class InMemoryAddonStorage : AddonStorage {
    private val values = ConcurrentHashMap<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        values[key] = value
    }

    override fun delete(key: String): Boolean = values.remove(key) != null

    override fun keys(): List<String> = values.keys.toList()
}
