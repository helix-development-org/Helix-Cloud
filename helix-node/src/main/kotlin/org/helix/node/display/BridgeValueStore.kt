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
     * Republishing a key under a different owner transfers ownership: the
     * previous owner relinquishes it, so that owner being disabled later
     * does not delete a value it no longer actually owns.
     *
     * @param owner owning addon id.
     * @param key value key.
     * @param value value text.
     */
    fun publish(owner: String, key: String, value: String) {
        values[key] = value
        owners.forEach { (otherOwner, keys) -> if (otherOwner != owner) keys.remove(key) }
        owners.computeIfAbsent(owner) { ConcurrentHashMap.newKeySet() }.add(key)
    }

    /**
     * Removes a single value, if it is owned by [owner].
     *
     * A no-op when [owner] does not currently own [key] — most commonly
     * because another addon has since republished the same key.
     *
     * @param owner owning addon id.
     * @param key value key.
     */
    fun unpublish(owner: String, key: String) {
        if (owners[owner]?.remove(key) == true) {
            values.remove(key)
        }
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

    /**
     * Snapshot filtered to values whose owning addon passes [ownerActive].
     *
     * Used to hide values of addons that are disabled for a given task.
     *
     * @param ownerActive predicate deciding whether an owner's values show.
     * @return key to value map for allowed owners only.
     */
    fun all(ownerActive: (owner: String) -> Boolean): Map<String, String> {
        val allowedKeys = owners.entries
            .filter { ownerActive(it.key) }
            .flatMap { it.value }
            .toSet()
        return values.filterKeys { it in allowedKeys }
    }
}
