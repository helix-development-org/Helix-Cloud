package org.helix.node.messages

import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry of every addon's [MessageBundle], so the dashboard can
 * list and edit all addon messages in one place.
 */
class MessageRegistry {
    private val bundles = ConcurrentHashMap<String, MessageBundle>()

    /**
     * Registers (or replaces) an addon's bundle.
     *
     * @param addonId owning addon id.
     * @param bundle the addon's message bundle.
     */
    fun register(addonId: String, bundle: MessageBundle) {
        bundles[addonId] = bundle
    }

    /**
     * Removes an addon's bundle, on disable.
     *
     * @param addonId owning addon id.
     */
    fun unregisterOwner(addonId: String) {
        bundles.remove(addonId)
    }

    /**
     * All messages grouped by addon id.
     *
     * @return addon id to (message key to template), sorted by addon id.
     */
    fun all(): Map<String, Map<String, String>> =
        bundles.toSortedMap().mapValues { it.value.all() }

    /**
     * Updates one message.
     *
     * @param addonId owning addon id.
     * @param key message key.
     * @param value new template.
     * @return `true` if the addon and key exist.
     */
    fun set(addonId: String, key: String, value: String): Boolean =
        bundles[addonId]?.set(key, value) ?: false

    /**
     * Resets one message to its default.
     *
     * @param addonId owning addon id.
     * @param key message key.
     * @return `true` if the addon and key exist.
     */
    fun reset(addonId: String, key: String): Boolean =
        bundles[addonId]?.reset(key) ?: false
}
