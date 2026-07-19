package org.helix.node.display

import java.util.concurrent.ConcurrentHashMap

/**
 * Global key-value store addons publish into and bridges poll.
 *
 * Examples: `tablist.header`, `tablist.footer`, `chat.format`. Keys are
 * tracked per owner so an addon's values disappear when it is disabled.
 */
class BridgeValueStore {
    private val values = ConcurrentHashMap<String, String>()
    private val owners = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Publishes or overwrites a value.
     *
     * @param owner owning addon id.
     * @param key value key.
     * @param value value text.
     */
    fun publish(owner: String, key: String, value: String) {
        values[key] = value
        owners.computeIfAbsent(owner) { ConcurrentHashMap.newKeySet() }.add(key)
    }

    /**
     * Removes all values of an owner.
     *
     * @param owner the owning addon id.
     */
    fun unpublishOwner(owner: String) {
        owners.remove(owner)?.forEach(values::remove)
    }

    /**
     * Snapshot of all published values.
     *
     * @return key to value map.
     */
    fun all(): Map<String, String> = values.toMap()
}
