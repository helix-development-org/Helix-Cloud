package de.tytoss.iguard.ban

import de.tytoss.iguard.api.BanProvider
import de.tytoss.iguard.config.BansConfig
import de.tytoss.iguard.notify.NotificationService
import de.tytoss.iguard.storage.GuardStore
import org.bukkit.Bukkit
import java.util.UUID
import java.util.logging.Logger

/**
 * Single entry point for every ban/unban in IGuard (auto-enforcement, manual command, panel). Always
 * writes IGuard's own audit (punishment log) and fires notifications, then delegates the actual
 * enforcement to the configured [BanProvider] so operators can keep whatever ban system they use.
 */
class BanCoordinator(
    private val config: BansConfig,
    private val helix: BanProvider,
    private val command: BanProvider,
    private val storage: GuardStore,
    private val notifications: NotificationService,
    private val logger: Logger,
) {
    /** Records the audit entry + notification, then bans through the configured provider. */
    fun ban(playerId: UUID, playerName: String, hours: Int, reason: String, actor: String) {
        storage.enqueuePunishment(playerId, playerName, "BAN", hours, reason, actor)
        notifications.ban(playerName, hours, reason, actor)
        runCatching { provider().ban(playerId, playerName, hours, reason, actor) }
            .onFailure { logger.warning("Ban provider '${provider().id}' failed: ${it.message}") }
    }

    /** Records the audit entry, then lifts the ban through the configured provider. */
    fun unban(playerId: UUID, playerName: String, actor: String) {
        storage.enqueuePunishment(playerId, playerName, "UNBAN", null, "Unban", actor)
        runCatching { provider().unban(playerId, playerName, actor) }
            .onFailure { logger.warning("Ban provider '${provider().id}' unban failed: ${it.message}") }
    }

    /** True when the active provider runs its own login gate + kick (never the helix backend). */
    fun ownsEnforcementGate(): Boolean = provider().ownsEnforcementGate

    /**
     * Resolved per call so a bridge plugin can register its provider after IGuard has enabled.
     * `native` (the legacy standalone backend) is an alias of the helix provider: the node owns
     * network-wide enforcement, so there is no separate local ban table anymore.
     */
    private fun provider(): BanProvider = when (config.provider) {
        "command" -> command
        "service" -> externalService() ?: helix
        else -> helix
    }

    private fun externalService(): BanProvider? {
        // Any registered BanProvider that isn't one of ours (avoids selecting IGuard's own native/helix).
        val registrations = Bukkit.getServicesManager().getRegistrations(BanProvider::class.java)
        return registrations.map { it.provider }.firstOrNull { it.id != "native" && it.id != "command" && it.id != "helix" }
    }
}
