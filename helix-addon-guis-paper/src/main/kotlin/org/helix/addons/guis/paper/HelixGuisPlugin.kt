package org.helix.addons.guis.paper

import de.tytoss.igui.IGui
import de.tytoss.igui.display.GuiFontConfiguration
import de.tytoss.igui.registerShared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin

/**
 * Shared IGui install for the whole network: the one plugin that actually
 * calls [IGui.install], so every other addon's menu gets a correctly and
 * identically configured instance (font namespace, node-backed texture
 * database) via [de.tytoss.igui.awaitSharedIGui] instead of each addon
 * installing — and potentially misconfiguring — its own.
 */
class HelixGuisPlugin : JavaPlugin() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    @Volatile private var igui: IGui? = null
    @Volatile private var client: GuisNodeClient? = null

    /** Reads the node connection from the environment and installs the shared IGui instance. */
    override fun onEnable() {
        val controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty()
        val controlToken = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        if (controlUrl.isBlank() || controlToken.isBlank()) {
            logger.severe("Helix-GUIs requires HELIX_CONTROL_URL and HELIX_CONTROL_TOKEN")
            server.pluginManager.disablePlugin(this)
            return
        }
        val client = GuisNodeClient(controlUrl, controlToken)
        this.client = client
        scope.launch {
            val installed = IGui.install(this@HelixGuisPlugin) {
                // The one namespace every dependent addon's DEFAULT font (title text, invisible
                // cursor-spacing glyphs) resolves against — see helix-cloud-project's Profile/Guard/
                // BetterMsgs menu history for why this used to be configured (or forgotten) per-addon.
                fonts = GuiFontConfiguration(namespace = "helix_guis")
                database(NodeGuiTextureDatabase(client))
            }
            igui = installed
            installed.registerShared(this@HelixGuisPlugin)
            logger.info("Helix-GUIs ready (${installed.metrics.loadedTextures} cached texture(s))")
        }
    }

    /** Shuts the shared IGui instance down, if it finished installing. */
    override fun onDisable() {
        igui?.let { runBlocking { it.shutdown() } }
        scope.cancel()
        client?.close()
        client = null
    }
}
