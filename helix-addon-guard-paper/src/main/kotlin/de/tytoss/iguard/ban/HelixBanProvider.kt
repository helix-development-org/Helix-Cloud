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

    override fun unban(playerId: UUID, playerName: String, actor: String) {
        storage.submitUnban(playerId, playerName)
    }
}
