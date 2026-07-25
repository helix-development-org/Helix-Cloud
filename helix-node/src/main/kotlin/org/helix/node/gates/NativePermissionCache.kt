package org.helix.node.gates

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player snapshot of the Minecraft-native permission nodes a player holds.
 *
 * Populated by the proxy bridge on join (which evaluates the nodes the node
 * advertises via `hasPermission`) and cleared on leave. Backs the
 * [NativePermissionProvider] used when no permission addon is active.
 */
class NativePermissionCache {
    private val granted = ConcurrentHashMap<String, Set<String>>()

    /**
     * Records the nodes a player was granted natively.
     *
     * @param name player name (case-insensitive).
     * @param nodes the granted permission nodes.
     */
    fun update(name: String, nodes: List<String>) {
        granted[name.lowercase()] = nodes.toSet()
    }

    /**
     * Drops a player's snapshot when they leave.
     *
     * @param name player name (case-insensitive).
     */
    fun clear(name: String) {
        granted.remove(name.lowercase())
    }

    /**
     * Returns a player's granted nodes, or `null` when the player is unknown.
     *
     * @param name player name (case-insensitive).
     * @return the granted nodes, or `null` if no snapshot exists.
     */
    fun granted(name: String): Set<String>? = granted[name.lowercase()]

    /**
     * Snapshot of every player's granted nodes, for the restart state.
     *
     * @return lowercase player name to granted nodes.
     */
    fun snapshot(): Map<String, Set<String>> = granted.toMap()

    /**
     * Restores snapshots after a node restart.
     *
     * @param snapshots lowercase player name to granted nodes.
     */
    fun restore(snapshots: Map<String, Set<String>>) {
        snapshots.forEach { (name, nodes) -> granted.putIfAbsent(name, nodes) }
    }
}
