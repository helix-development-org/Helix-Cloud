package de.tytoss.iguard.api

import de.tytoss.iguard.check.CheckEngine
import java.time.Duration
import java.util.UUID

/** Default [IGuardApi] implementation backed by the check engine, exemptions and ban coordinator. */
class IGuardApiImpl(
    private val engine: CheckEngine,
    private val exemptions: ExemptionManager,
    private val bans: de.tytoss.iguard.ban.BanCoordinator
) : IGuardApi {
    override fun snapshot(playerId: UUID) = engine.snapshot(playerId)

    override fun latestIncident(playerId: UUID) = engine.incidentSnapshot(playerId)

    override fun exempt(playerId: UUID, duration: Duration, reason: String) = exemptions.exempt(playerId, duration, reason)

    override fun isExempt(playerId: UUID) = exemptions.isExempt(playerId)

    override fun ban(playerId: UUID, playerName: String, hours: Int, reason: String, actor: String) =
        bans.ban(playerId, playerName, hours, reason, actor)

    override fun unban(playerId: UUID, playerName: String, actor: String) =
        bans.unban(playerId, playerName, actor)
}
