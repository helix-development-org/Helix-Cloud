package org.helix.bridge.velocity

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component

/**
 * Registers the player-facing proxy commands `/lobby`, `/server` and
 * `/servers`.
 *
 * Every message is a translation key under
 * `helix.translations.velocity.command.*`, rendered in the player's
 * language; the `/servers` list is clickable (click to connect).
 *
 * @property proxy the Velocity proxy.
 * @property registry managed backends.
 * @property translate renders a translation key for a player: key, fallback
 *  template and extra placeholder values.
 */
class ProxyCommands(
    private val proxy: ProxyServer,
    private val registry: BackendRegistry,
    private val translate: (Player?, String, String, Map<String, String>) -> Component,
) {
    /**
     * Registers all commands.
     *
     * @param plugin the plugin instance used as command owner.
     */
    fun register(plugin: Any) {
        val manager = proxy.commandManager
        manager.register(manager.metaBuilder("lobby").plugin(plugin).build(), lobbyCommand())
        manager.register(manager.metaBuilder("server").plugin(plugin).build(), serverCommand())
        manager.register(manager.metaBuilder("servers").plugin(plugin).build(), serversCommand())
    }

    private fun message(
        source: CommandSource,
        key: String,
        fallback: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        source.sendMessage(translate(source as? Player, "helix.translations.velocity.$key", fallback, extra))
    }

    private fun lobbyCommand() = SimpleCommand { invocation ->
        val player = invocation.source() as? Player ?: return@SimpleCommand
        val lobby = registry.fallback(player.currentServer.map { it.serverInfo.name }.orElse(null))
        if (lobby == null) {
            message(player, "command.lobby.none", "No lobby available.")
        } else {
            player.createConnectionRequest(lobby).fireAndForget()
        }
    }

    private fun serverCommand() = SimpleCommand { invocation ->
        val player = invocation.source() as? Player ?: return@SimpleCommand
        val target = invocation.arguments().firstOrNull()
        if (target == null) {
            message(player, "command.server.usage", "Usage: /server <name>")
            return@SimpleCommand
        }
        val server = proxy.getServer(target).orElse(null)
        if (server == null) {
            message(player, "command.server.unknown", "Unknown server: {server}", mapOf("server" to target))
        } else {
            player.createConnectionRequest(server).fireAndForget()
        }
    }

    private fun serversCommand() = SimpleCommand { invocation ->
        val source = invocation.source()
        val names = registry.backendNames()
        if (names.isEmpty()) {
            message(source, "command.servers.none", "No servers registered.")
            return@SimpleCommand
        }
        message(source, "command.servers.header", "Servers ({count}):", mapOf("count" to names.size.toString()))
        names.sorted().forEach { name ->
            message(source, "command.servers.entry", " • {server}", mapOf("server" to name))
        }
    }
}
