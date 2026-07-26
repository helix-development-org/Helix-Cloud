package de.tytoss.iguard.api

import java.util.UUID

/**
 * Pluggable ban backend. IGuard always records its own audit (punishment log) and fires notifications;
 * a BanProvider owns the actual enforcement mechanism — blocking the player and their rejoin.
 *
 * Three ways to choose one (config `bans.provider`):
 *  - `native`  — IGuard's built-in network bans + login gate (default).
 *  - `command` — delegate to any ban plugin via configurable console commands.
 *  - `service` — use a BanProvider another plugin registered with Bukkit's ServicesManager, so a bridge
 *                can call LiteBans / LibertyBans / AdvancedBan / a custom system directly.
 *
 * Other plugins integrate by registering an implementation:
 *   Bukkit.getServicesManager().register(BanProvider::class.java, myProvider, plugin, ServicePriority.Normal)
 */
interface BanProvider {
    /** Stable identifier, e.g. "native", "litebans". */
    val id: String

    /** Ban a player for [hours] hours (treat >= 8760 as effectively permanent). */
    fun ban(playerId: UUID, playerName: String, hours: Int, reason: String, actor: String)

    /** Lift a player's ban. */
    fun unban(playerId: UUID, playerName: String, actor: String)

    /** True if IGuard should run its own login/rejoin gate + local kick; external systems own their own. */
    val ownsEnforcementGate: Boolean get() = false
}
