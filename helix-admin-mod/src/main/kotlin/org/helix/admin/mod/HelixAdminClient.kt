package org.helix.admin.mod

import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import net.dimaskama.mcef.api.MCEFApi
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Entry point of the Helix Admin client mod: Right Shift opens the in-game admin panel of a Helix network.
 *
 * As soon as the server registers the channel `helix:admin` (network running the `helix.admin` addon) the mod
 * says `hello`. Pressing the key always asks the server directly (`open`); it answers with a one-time login
 * `ticket` (the panel page opens in the embedded browser) or `denied`. Every outcome is shown in the action bar,
 * so a missing permission or a server without the panel is never silent. Later presses reopen the page as it
 * was left.
 *
 * The quick menu opens as a HUD layer: the player keeps walking and looking around and steers the menu with the
 * arrow keys, Enter and Backspace; Right Alt (or an input field / the full dashboard) switches to mouse and typing
 * ([AdminScreen]), Escape goes back. Right Shift closes the HUD menu.
 */
object HelixAdminClient : ClientModInitializer {
    /** Version of this mod, sent in `hello`. */
    val VERSION: String by lazy {
        FabricLoader.getInstance().getModContainer("helix-admin").map { it.metadata.version.friendlyString }.orElse("dev")
    }

    private val CATEGORY = KeyMapping.Category(Identifier.fromNamespaceAndPath("helix-admin", "main"))

    /** The panel key (default Right Shift, rebindable in the controls). */
    val openKey: KeyMapping = KeyMapping("key.helix-admin.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY)

    /** Switches between the walkable HUD menu and mouse/typing mode (default Right Alt / Option). */
    val mouseKey: KeyMapping = KeyMapping("key.helix-admin.mouse", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_ALT, CATEGORY)

    /** Navigation of the HUD menu: page event name → key (arrows, Enter, Backspace by default). */
    private val navKeys: List<Pair<String, KeyMapping>> = listOf(
        "up" to GLFW.GLFW_KEY_UP,
        "down" to GLFW.GLFW_KEY_DOWN,
        "left" to GLFW.GLFW_KEY_LEFT,
        "right" to GLFW.GLFW_KEY_RIGHT,
        "select" to GLFW.GLFW_KEY_ENTER,
        "back" to GLFW.GLFW_KEY_BACKSPACE,
    ).map { (name, key) -> name to KeyMapping("key.helix-admin.nav_$name", InputConstants.Type.KEYSYM, key, CATEGORY) }

    private val LOG = org.slf4j.LoggerFactory.getLogger("helix-admin")

    @Volatile
    private var awaitingTicket = false

    override fun onInitializeClient() {
        MCEFApi.initialize() // lädt Chromium im Hintergrund (beim ersten Start einmalig herunter)
        AdminChannel.registerTypes()
        KeyBindingHelper.registerKeyBinding(openKey)
        KeyBindingHelper.registerKeyBinding(mouseKey)
        navKeys.forEach { KeyBindingHelper.registerKeyBinding(it.second) }
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("helix-admin", "panel")) { graphics, _ ->
            if (PanelBrowser.mode == PanelBrowser.Mode.HUD) {
                val mc = Minecraft.getInstance()
                PanelBrowser.draw(graphics, graphics.guiWidth(), graphics.guiHeight(), mc.font)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(AdminChannel.TYPE) { payload, context ->
            context.client().execute { onMessage(payload.json) }
        }
        // hello erst, wenn der Server den Kanal angemeldet hat (beim JOIN ist das oft noch nicht der Fall)
        C2SPlayChannelEvents.REGISTER.register { _, _, _, channels ->
            if (channels.any { it == AdminChannel.TYPE.id() }) {
                LOG.info("server offers helix:admin")
                AdminChannel.send(JsonObject().apply {
                    addProperty("type", "hello")
                    addProperty("version", VERSION)
                })
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            awaitingTicket = false
            AdminChannel.reset()
            Minecraft.getInstance().execute { PanelBrowser.release() }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client -> onTick(client) }
    }

    private fun onTick(client: Minecraft) {
        // Maus-Modus von einem anderen Screen verdrängt (Chat, Tod, …) → zurück ins HUD-Menü
        if (PanelBrowser.mode == PanelBrowser.Mode.MOUSE && client.screen !is AdminScreen) PanelBrowser.showHud()
        while (openKey.consumeClick()) {
            if (client.screen != null) continue
            if (PanelBrowser.mode == PanelBrowser.Mode.HUD) PanelBrowser.close() else onKeyPressed(client)
        }
        val hud = PanelBrowser.mode == PanelBrowser.Mode.HUD && client.screen == null
        while (mouseKey.consumeClick()) if (hud) PanelBrowser.showMouse()
        for ((name, key) in navKeys) {
            while (key.consumeClick()) if (hud) PanelBrowser.nav(name)
        }
    }

    private fun onKeyPressed(client: Minecraft) {
        LOG.info("panel key pressed")
        if (!ClientPlayNetworking.canSend(AdminChannel.TYPE)) {
            status(client, "helix-admin.unavailable")
            LOG.info("key pressed, but the server does not offer helix:admin")
            return
        }
        val init = MCEFApi.initialize()
        val future = init.future
        if (future.isCompletedExceptionally) {
            val cause = runCatching { future.join() }.exceptionOrNull()?.let { it.cause ?: it }
            LOG.error("embedded browser failed to start", cause)
            status(client, "helix-admin.browser_failed", cause?.message ?: cause?.javaClass?.simpleName ?: "?")
            return
        }
        if (!future.isDone) {
            LOG.info("browser not ready yet: {} {}%", init.stage, (init.percentage * 100).toInt())
            status(client, "helix-admin.loading", init.stage.name.lowercase(), (init.percentage * 100).toInt())
            return
        }
        if (PanelBrowser.ready) {
            PanelBrowser.showHud()
            return
        }
        if (awaitingTicket) {
            status(client, "helix-admin.opening")
            return
        }
        awaitingTicket = AdminChannel.send(JsonObject().apply { addProperty("type", "open") })
        LOG.info("open requested: {}", awaitingTicket)
        if (awaitingTicket) status(client, "helix-admin.opening")
    }

    /** Status line in the chat – the action bar is overwritten by server HUDs every tick. */
    private fun status(client: Minecraft, key: String, vararg args: Any) {
        client.player?.displayClientMessage(Component.translatable(key, *args), false)
    }

    private fun onMessage(json: JsonObject) {
        when (json.get("type")?.asString) {
            "welcome" -> LOG.info("helix:admin welcome, panel allowed: {}", json.get("enabled")?.asBoolean)
            "denied" -> {
                awaitingTicket = false
                LOG.info("panel denied: {}", json.get("reason")?.asString)
                status(Minecraft.getInstance(), if (json.get("reason")?.asString == "unreachable") "helix-admin.unreachable" else "helix-admin.denied")
            }
            "ticket" -> {
                awaitingTicket = false
                val url = json.get("url")?.asString ?: return
                LOG.info("ticket received, opening {}", url.substringBefore('#'))
                runCatching {
                    PanelBrowser.load(url)
                    PanelBrowser.showHud()
                }.onFailure {
                    LOG.error("could not open the panel", it)
                    status(Minecraft.getInstance(), "helix-admin.browser_failed", it.message ?: it.javaClass.simpleName)
                }
            }
            "result" -> AdminChannel.onResult(json)
            "close" -> PanelBrowser.close()
        }
    }
}
