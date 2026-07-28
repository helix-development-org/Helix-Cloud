package org.helix.bridge.paper

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Player
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.plugin.Plugin

/**
 * Mirrors the node's permission decisions onto Bukkit's native permission
 * system through one [PermissionAttachment] per online player.
 *
 * This makes the network's own permissions addon act as a real,
 * transparent permission provider on the Paper side: any third-party
 * plugin calling `Player#hasPermission` sees exactly the nodes the node
 * resolved, with zero coupling to any specific permission plugin (no
 * LuckPerms required or assumed).
 *
 * @property plugin owning plugin, required to create attachments.
 */
class HelixPermissionProvider(private val plugin: Plugin) {
    private val attachments = ConcurrentHashMap<UUID, PermissionAttachment>()
    private val granted = ConcurrentHashMap<UUID, Set<String>>()

    /**
     * Applies the resolved permission set to a player, granting newly added
     * nodes and revoking ones no longer held. A no-op when nothing changed.
     *
     * @param player the online player.
     * @param nodes the permission nodes the node granted.
     */
    fun sync(player: Player, nodes: Set<String>) {
        val current = granted[player.uniqueId] ?: emptySet()
        val diff = PermissionSetDiff.diff(current, nodes)
        if (diff.isEmpty()) {
            return
        }
        val attachment = attachments.getOrPut(player.uniqueId) { player.addAttachment(plugin) }
        // Each call recalculates the owning permissible's effective permissions.
        diff.toGrant.forEach { node -> attachment.setPermission(node, true) }
        diff.toRevoke.forEach { node -> attachment.unsetPermission(node) }
        granted[player.uniqueId] = nodes
    }

    /**
     * Removes a player's attachment and cached state, called on quit.
     *
     * @param player the leaving player.
     */
    fun clear(player: Player) {
        attachments.remove(player.uniqueId)?.let { attachment ->
            runCatching { player.removeAttachment(attachment) }
        }
        granted.remove(player.uniqueId)
    }
}
