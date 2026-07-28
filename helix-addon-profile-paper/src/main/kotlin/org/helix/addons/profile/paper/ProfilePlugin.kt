package org.helix.addons.profile.paper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * Graphical `/profilemenu` for the profile addon.
 *
 * `/profile` itself is a network-wide, node-routed player command (a plain
 * text summary that works everywhere, including on Velocity and from the
 * console) — Velocity registers it as a proxy command and intercepts it
 * before it ever reaches a backend server, so this plugin cannot reuse
 * that same name for the graphical menu. `/profilemenu` is a genuinely
 * separate, Paper-only command instead.
 */
class ProfilePlugin : JavaPlugin() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private lateinit var gui: ProfileGuiService

    /** Reads the node connection from the environment and installs the GUI. */
    override fun onEnable() {
        val controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty()
        val controlToken = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        if (controlUrl.isBlank()) {
            logger.severe("HelixProfile requires the HELIX_CONTROL_URL environment variable")
            server.pluginManager.disablePlugin(this)
            return
        }
        val client = ProfileNodeClient(controlUrl, controlToken)
        gui = ProfileGuiService(this, client, scope)
        gui.install()
        logger.info("HelixProfile enabled (node: $controlUrl)")
    }

    /** Shuts the GUI down and cancels this plugin's coroutine scope. */
    override fun onDisable() {
        if (::gui.isInitialized) {
            gui.shutdown()
        }
        scope.cancel()
    }

    /** Opens the profile menu for the executing player. */
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage("Only players can open the profile menu.")
            return true
        }
        gui.open(player)
        return true
    }
}
