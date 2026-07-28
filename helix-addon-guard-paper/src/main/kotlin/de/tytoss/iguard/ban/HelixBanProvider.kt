package de.tytoss.iguard.ban

import de.tytoss.iguard.api.BanProvider
import de.tytoss.iguard.storage.HelixNodeStore
import java.util.UUID

/**
 * Helix-Cloud ban backend: posts guard.store.ban / guard.store.unban node actions through the
 * [HelixNodeStore]. The node enforces network-wide itself (kick + join gate on every server), so this
 * provider never kicks locally and does not own IGuard's own enforcement gate.
 */
class HelixBanProvider(private val storage: HelixNodeStore) : BanProvider {
    override val id = "helix"
    override val ownsEnforcementGate = false

    override fun ban(playerId: UUID, playerName: String, hours: Int, reason: String, actor: String) {
        storage.submitBan(playerId, playerName, hours, reason, actor)
    }

    /**
     * Lifts the ban, working whether or not [playerId] is currently online and whether or not the
     * node is reachable at this exact moment.
     *
     * Tries [HelixNodeStore.revokeBanBlocking] first: a synchronous `guard.store.unban` call (built
     * for exactly this — see its KDoc — but never actually invoked before this fix, so an offline
     * unban silently fell through to the fire-and-forget path below on every call). When that
     * immediate attempt fails (node briefly unreachable), it falls back to
     * [HelixNodeStore.submitUnban], which queues the action for the writer thread to retry against
     * the node indefinitely (see `HelixNodeStore.writerLoop`) instead of the unban being lost outright.
     * Neither path depends on [playerId] being online: the node's ban table is keyed by uuid, and
     * `revokeBanBlocking` resolves the player's last-known name from Bukkit's offline-player cache.
     */
    override fun unban(playerId: UUID, playerName: String, actor: String) {
        if (!storage.revokeBanBlocking(playerId)) {
            storage.submitUnban(playerId, playerName)
        }
    }
}
