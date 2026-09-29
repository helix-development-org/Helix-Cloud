package org.helix.admin.mod

import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import net.dimaskama.mcef.api.MCEFApi
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
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
 */
object HelixAdminClient : ClientModInitializer {
    /** Version of this mod, sent in `hello`. */
    val VERSION: String by lazy {
        FabricLoader.getInstance().getModContainer("helix-admin").map { it.metadata.version.friendlyString }.orElse("dev")
    }

    private val CATEGORY = KeyMapping.Category(Identifier.fromNamespaceAndPath("helix-admin", "main"))

    /** The panel key (default Right Shift, rebindable in the controls). */
    val openKey: KeyMapping = KeyMapping("key.helix-admin.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY)

    private val LOG = org.slf4j.LoggerFactory.getLogger("helix-admin")

    @Volatile
    private var awaitingTicket = false

    override fun onInitializeClient() {
        MCEFApi.initialize() // lädt Chromium im Hintergrund (beim ersten Start einmalig herunter)
        AdminChannel.registerTypes()
        KeyBindingHelper.registerKeyBinding(openKey)

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
            Minecraft.getInstance().execute { AdminScreen.release() }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openKey.consumeClick()) {
                if (client.screen == null) onKeyPressed(client)
            }
        }
    }

    private fun onKeyPressed(client: Minecraft) {
        if (!ClientPlayNetworking.canSend(AdminChannel.TYPE)) {
            status(client, "helix-admin.unavailable")
            LOG.info("key pressed, but the server does not offer helix:admin")
            return
        }
        val init = MCEFApi.initialize()
        if (!init.isDone) {
            client.player?.displayClientMessage(
                Component.translatable("helix-admin.loading", (init.percentage * 100).toInt()), true,
            )
            return
        }
        if (AdminScreen.reopen()) return
        if (awaitingTicket) return
        awaitingTicket = AdminChannel.send(JsonObject().apply { addProperty("type", "open") })
        if (awaitingTicket) status(client, "helix-admin.opening")
    }

    private fun status(client: Minecraft, key: String) {
        client.player?.displayClientMessage(Component.translatable(key), true)
    }

    private fun onMessage(json: JsonObject) {
        when (json.get("type")?.asString) {
            "welcome" -> LOG.info("helix:admin welcome, panel allowed: {}", json.get("enabled")?.asBoolean)
            "denied" -> {
                awaitingTicket = false
                status(Minecraft.getInstance(), if (json.get("reason")?.asString == "unreachable") "helix-admin.unreachable" else "helix-admin.denied")
            }
            "ticket" -> {
                awaitingTicket = false
                val url = json.get("url")?.asString ?: return
                AdminScreen.open(url)
            }
            "result" -> AdminChannel.onResult(json)
            "close" -> AdminScreen.closeIfOpen()
        }
    }
}
