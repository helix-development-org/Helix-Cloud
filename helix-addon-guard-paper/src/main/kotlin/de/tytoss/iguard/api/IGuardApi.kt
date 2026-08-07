package de.tytoss.iguard.api

import de.tytoss.iguard.model.IncidentSnapshot
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Public read model of a player's live IGuard state (fingerprint, VLs, packet metrics). */
data class IGuardPlayerSnapshot(
    val playerId: UUID,
    val playerName: String,
    val clientVersion: String,
    val clientBrand: String?,
    val clientFamily: String,
    val clientConfidence: String,
    val clientChannels: Set<String>,
    val supported: Boolean,
    val exemptUntil: Instant?,
    val violationLevels: Map<String, Double>,
    val droppedPackets: Long,
    val lastPacketAt: Instant?,
)

/** Handle for a granted exemption; cancelling it re-enables checks early. */
interface IGuardExemption {
    val playerId: UUID
    val reason: String
    val expiresAt: Instant

    /** Revokes the exemption; false when it was already cancelled or replaced. */
    fun cancel(): Boolean
}

/** Public service other plugins can obtain via Bukkit's ServicesManager to integrate with IGuard. */
interface IGuardApi {
    /** Live state for an online player, or null before any packet was observed. */
    fun snapshot(playerId: UUID): IGuardPlayerSnapshot?

    /** The player's most recent open incident (case), or null. */
    fun latestIncident(playerId: UUID): IncidentSnapshot?

    /** Temporarily whitelists a player from all checks. */
    fun exempt(playerId: UUID, duration: Duration, reason: String): IGuardExemption

    /** True while the player holds an active exemption. */
    fun isExempt(playerId: UUID): Boolean

    /** Issue a ban through IGuard's coordinator (audit + notify + the configured ban provider). */
    fun ban(playerId: UUID, playerName: String, hours: Int, reason: String, actor: String)

    /** Lift a ban through IGuard's coordinator. */
    fun unban(playerId: UUID, playerName: String, actor: String)
}
