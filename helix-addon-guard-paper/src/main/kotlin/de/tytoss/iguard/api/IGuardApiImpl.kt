package de.tytoss.iguard.api

import de.tytoss.iguard.check.CheckEngine
import de.tytoss.iguard.config.IGuardConfig
import de.tytoss.iguard.trace.TraceControl
import java.time.Duration
import java.util.UUID

/** Default [IGuardApi] implementation backed by the check engine, exemptions, traces and ban coordinator. */
class IGuardApiImpl(
    private val engine: CheckEngine,
    private val exemptions: ExemptionManager,
    private val bans: de.tytoss.iguard.ban.BanCoordinator,
    private val traces: TraceControl,
) : IGuardApi {
    override fun snapshot(playerId: UUID) = engine.snapshot(playerId)

    override fun latestIncident(playerId: UUID) = engine.incidentSnapshot(playerId)

    override fun exempt(playerId: UUID, duration: Duration, reason: String) = exemptions.exempt(playerId, duration, reason)

    override fun exempt(playerId: UUID, duration: Duration, reason: String, scope: IGuardExemptionScope) =
        exemptions.exempt(playerId, duration, reason, scope)

    override fun isExempt(playerId: UUID) = exemptions.isExempt(playerId)

    override fun ban(playerId: UUID, playerName: String, hours: Int, reason: String, actor: String) =
        bans.ban(playerId, playerName, hours, reason, actor)

    override fun unban(playerId: UUID, playerName: String, actor: String) =
        bans.unban(playerId, playerName, actor)

    override fun checkIds(): Set<String> = IGuardConfig.checkIds

    override fun startTrace(playerId: UUID, label: String) = traces.start(playerId, label)

    override fun stopTrace(playerId: UUID) = traces.stop(playerId)

    override fun traceMarker(playerId: UUID, text: String) = traces.marker(playerId, text)

    override fun activeTrace(playerId: UUID) = traces.active(playerId)

    override fun addTraceListener(listener: IGuardTraceListener) = traces.addListener(listener)
}
