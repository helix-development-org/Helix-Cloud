package org.helix.bridge.velocity

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component

/**
 * Registers the player-facing proxy commands `/lobby`, `/server` and
 * `/servers`.
 *
 * @property proxy the Velocity proxy.
 * @property registry managed backends.
 */
class ProxyCommands(
    private val proxy: ProxyServer,
    private val registry: BackendRegistry,
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

    private fun lobbyCommand() = SimpleCommand { invocation ->
        val player = invocation.source() as? Player ?: return@SimpleCommand
        val lobby = registry.fallback(player.currentServer.map { it.serverInfo.name }.orElse(null))
        if (lobby == null) {
            player.sendMessage(Component.text("No lobby available."))
        } else {
            player.createConnectionRequest(lobby).fireAndForget()
        }
    }

    private fun serverCommand() = SimpleCommand { invocation ->
        val player = invocation.source() as? Player ?: return@SimpleCommand
        val target = invocation.arguments().firstOrNull()
        if (target == null) {
            player.sendMessage(Component.text("Usage: /server <name>"))
            return@SimpleCommand
        }
        val server = proxy.getServer(target).orElse(null)
        if (server == null) {
            player.sendMessage(Component.text("Unknown server: $target"))
        } else {
            player.createConnectionRequest(server).fireAndForget()
        }
    }

    private fun serversCommand() = SimpleCommand { invocation ->
        val names = registry.backendNames()
        val text = if (names.isEmpty()) "No servers registered." else "Servers: ${names.joinToString()}"
        invocation.source().sendMessage(Component.text(text))
    }
}
