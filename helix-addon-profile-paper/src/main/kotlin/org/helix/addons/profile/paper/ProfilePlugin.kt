package org.helix.addons.profile.paper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.i18n.NodeTranslations

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
    private var client: ProfileNodeClient? = null
    private var translations: NodeTranslations? = null

    /** Reads the node connection from the environment and installs the GUI. */
    override fun onEnable() {
        val controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty()
        val controlToken = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        if (controlUrl.isBlank() || controlToken.isBlank()) {
            logger.severe("HelixProfile requires HELIX_CONTROL_URL and HELIX_CONTROL_TOKEN")
            server.pluginManager.disablePlugin(this)
            return
        }
        val client = ProfileNodeClient(controlUrl, controlToken)
        this.client = client
        val translations = NodeTranslations(controlUrl, controlToken, "helix.profile")
        this.translations = translations
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { translations.sync() }, 1L, TRANSLATION_SYNC_TICKS)
        gui = ProfileGuiService(this, client, translations, scope)
        gui.install()
        logger.info("HelixProfile enabled (node: $controlUrl)")
    }

    /** Shuts the GUI down and cancels this plugin's coroutine scope. */
    override fun onDisable() {
        if (::gui.isInitialized) {
            gui.shutdown()
        }
        scope.cancel()
        client?.close()
        client = null
        translations?.close()
        translations = null
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

    private companion object {
        /** How often the translation snapshot re-syncs from the node. */
        const val TRANSLATION_SYNC_TICKS = 100L
    }
}
