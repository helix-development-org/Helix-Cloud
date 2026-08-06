package org.helix.node.wire

import kotlinx.serialization.builtins.serializer
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionSource
import org.helix.api.action.PlayerCommandRequest
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.display.DisplayBulkRequest
import org.helix.api.display.DisplayProfile
import org.helix.api.i18n.TranslationsSnapshot
import org.helix.api.player.OnlinePlayer
import org.helix.api.player.PlayerEvent
import org.helix.api.player.PlayerLocaleReport
import org.helix.api.player.PlayerPermissionsReport
import org.helix.api.player.PlayerRosterReport
import org.helix.api.proxy.JoinRequest
import org.helix.api.proxy.PermissionCheckRequest
import org.helix.api.proxy.PermissionDecision
import org.helix.api.proxy.PlayerPermissionsSnapshot
import org.helix.node.control.ControlDependencies
import org.helix.api.bridge.NetworkPackInfo
import org.helix.node.control.applyPlayerEvent
import org.helix.node.control.knownPermissionNodes
import org.helix.wire.PlayerName
import org.helix.wire.PollAck
import org.helix.wire.RawJson
import org.helix.wire.WireCodec
import org.helix.wire.WireResponse
import org.helix.wire.WireServer

/**
 * Registers a Helix-Wire endpoint for every internal control call, so a
 * service reaches exactly the same node behaviour over the wire as it does
 * over the HTTP `internal` routes.
 *
 * Each endpoint delegates to the very same [ControlDependencies] services
 * the HTTP routes use — there is one behaviour, two transports. The wire
 * connection is already authenticated to a service id by the handshake, so
 * the per-call bridge-token check the HTTP side performs is implicit here;
 * where a call is scoped to a specific service (heartbeat, routing,
 * bridge-values) the connection's own id is the authority.
 *
 * The endpoint names mirror the HTTP paths without the `internal/` prefix,
 * so the mapping stays obvious.
 *
 * @property dependencies the shared control dependencies.
 */
class WireDispatch(private val dependencies: ControlDependencies) {
    /**
     * Registers every request/response endpoint on the wire server.
     *
     * @param server the wire server to register handlers on.
     */
    fun registerOn(server: WireServer) {
        server.handle("heartbeat") { _, payload ->
            val report = WireCodec.decode<HeartbeatReport>(payload)
            if (dependencies.manager.handleHeartbeat(report)) ok() else WireResponse.error("unknown service")
        }
        server.handle("routing") { serviceId, _ ->
            respond(
                dependencies.routing.snapshot(serviceId).copy(
                    networkName = dependencies.networkName(),
                    maintenanceScreen = dependencies.proxyScreens.raw("screen.maintenance"),
                    serverFullScreen = dependencies.proxyScreens.raw("screen.server_full"),
                ),
            )
        }
        server.handle("join-check") { _, payload ->
            respond(dependencies.joinGates.evaluate(WireCodec.decode<JoinRequest>(payload)))
        }
        server.handle("permission-check") { _, payload ->
            respond(PermissionDecision(dependencies.permissionService.check(WireCodec.decode<PermissionCheckRequest>(payload))))
        }
        server.handle("permission-nodes") { _, _ ->
            respond(knownPermissionNodes(dependencies))
        }
        server.handle("player-permissions-set") { _, payload ->
            val report = WireCodec.decode<PlayerPermissionsReport>(payload)
            dependencies.nativePermissions.update(report.name, report.permissions)
            ok()
        }
        server.handle("player-permissions-get") { _, payload ->
            val name = WireCodec.decode<PlayerName>(payload).name
            val granted = knownPermissionNodes(dependencies).filter { node ->
                dependencies.permissionService.check(PermissionCheckRequest(name = name, permission = node))
            }
            respond(PlayerPermissionsSnapshot(name = name, granted = granted))
        }
        server.handle("player-event") { _, payload ->
            if (applyPlayerEvent(dependencies, WireCodec.decode<PlayerEvent>(payload))) ok() else WireResponse.error("unknown event type")
        }
        server.handle("players") { _, _ ->
            respond(dependencies.playerRegistry.online())
        }
        server.handle("player-roster") { _, payload ->
            reconcileRoster(WireCodec.decode<PlayerRosterReport>(payload))
            ok()
        }
        server.handle("player-commands") { _, _ ->
            respond(dependencies.playerCommands.commands())
        }
        server.handle("player-command") { _, payload ->
            respond(dependencies.playerCommands.execute(WireCodec.decode<PlayerCommandRequest>(payload)))
        }
        server.handle("action") { _, payload ->
            respond(dependencies.bridgeActions.invoke(WireCodec.decode<ActionInvocation>(payload)))
        }
        server.handle("display") { _, payload ->
            respond(dependencies.displayResolvers.resolve(WireCodec.decode<JoinRequest>(payload).name))
        }
        server.handle("display-bulk") { _, payload ->
            val request = WireCodec.decode<DisplayBulkRequest>(payload)
            respond(request.names.associateWith { name -> dependencies.displayResolvers.resolve(name) }, DISPLAY_MAP)
        }
        server.handle("translations") { _, _ ->
            respond(translationsSnapshot())
        }
        server.handle("player-language") { _, payload ->
            val report = WireCodec.decode<PlayerLocaleReport>(payload)
            dependencies.languages.applyClientLocale(report.name, report.locale)
            ok()
        }
        server.handle("pack") { _, _ ->
            respond(NetworkPackInfo(sha1 = dependencies.networkPack.sha1()))
        }
        server.handle("bridge-values") { serviceId, _ ->
            val task = dependencies.manager.find(serviceId)?.task
            val values = if (task == null) emptyMap() else dependencies.bridgeValues.all { owner -> task.isAddonActive(owner) }
            respond(values, STRING_MAP)
        }
        server.handle("ban-snapshot") { _, _ ->
            val result = dependencies.registry.invoke(ActionInvocation("ban.export", emptyList(), ActionSource.SYSTEM))
            respond(RawJson(if (result.success) result.lines.firstOrNull() ?: "[]" else "[]"))
        }
        server.handle("poll-ack") { serviceId, payload ->
            dependencies.commandQueue.acknowledge(serviceId, WireCodec.decode<PollAck>(payload).ackUpTo)
            ok()
        }
    }

    private fun reconcileRoster(report: PlayerRosterReport) {
        val reportedNames = report.players.map { it.name.lowercase() }.toSet()
        val onlineViaProxy = dependencies.playerRegistry.online().filter { it.proxyServiceId == report.proxyServiceId }
        val onlineNames = onlineViaProxy.map { it.name.lowercase() }.toSet()
        report.players.filter { it.name.lowercase() !in onlineNames }.forEach { player ->
            applyPlayerEvent(dependencies, PlayerEvent("join", player.name, player.uuid, report.proxyServiceId))
        }
        onlineViaProxy.filter { it.name.lowercase() !in reportedNames }.forEach { player ->
            applyPlayerEvent(dependencies, PlayerEvent("leave", player.name, player.uuid, report.proxyServiceId))
        }
    }

    private fun translationsSnapshot(): TranslationsSnapshot {
        val online = dependencies.playerRegistry.online().map { it.name.lowercase() }.toSet()
        val languageList = dependencies.languages.languages()
        return TranslationsSnapshot(
            defaultLanguage = dependencies.languages.defaultLanguage(),
            languages = languageList,
            playerLanguages = dependencies.languages.playerLanguages().filterKeys { it in online },
            values = dependencies.messages.effectiveTables(languageList),
        )
    }

    private inline fun <reified T> respond(value: T): WireResponse = WireResponse.ok(WireCodec.encode(value))

    private fun <T> respond(value: T, serializer: kotlinx.serialization.KSerializer<T>): WireResponse =
        WireResponse.ok(WireCodec.encode(serializer, value))

    private fun ok(): WireResponse = WireResponse.ok()

    private companion object {
        val STRING_MAP = kotlinx.serialization.builtins.MapSerializer(
            String.serializer(),
            String.serializer(),
        )
        val DISPLAY_MAP = kotlinx.serialization.builtins.MapSerializer(
            String.serializer(),
            DisplayProfile.serializer(),
        )
    }
}
