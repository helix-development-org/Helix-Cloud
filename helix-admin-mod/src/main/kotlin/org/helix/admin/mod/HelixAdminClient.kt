package org.helix.admin.mod

import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import net.dimaskama.mcef.api.MCEFApi
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
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
 * On join the mod says `hello`; a network running the `helix.admin` addon answers `welcome` (only for players
 * allowed to use the panel). Pressing the key asks the server for a one-time login ticket and opens the panel
 * page with it in the embedded browser; later presses reopen the page as it was left.
 */
object HelixAdminClient : ClientModInitializer {
    /** Version of this mod, sent in `hello`. */
    val VERSION: String by lazy {
        FabricLoader.getInstance().getModContainer("helix-admin").map { it.metadata.version.friendlyString }.orElse("dev")
    }

    private val CATEGORY = KeyMapping.Category(Identifier.fromNamespaceAndPath("helix-admin", "main"))

    /** The panel key (default Right Shift, rebindable in the controls). */
    val openKey: KeyMapping = KeyMapping("key.helix-admin.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY)

    /** Whether the current server offered the panel to this player. */
    @Volatile
    private var enabled = false

    @Volatile
    private var awaitingTicket = false

    override fun onInitializeClient() {
        MCEFApi.initialize() // lädt Chromium im Hintergrund (beim ersten Start einmalig herunter)
        AdminChannel.registerTypes()
        KeyBindingHelper.registerKeyBinding(openKey)

        ClientPlayNetworking.registerGlobalReceiver(AdminChannel.TYPE) { payload, context ->
            context.client().execute { onMessage(payload.json) }
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            enabled = false
            AdminChannel.send(JsonObject().apply {
                addProperty("type", "hello")
                addProperty("version", VERSION)
            })
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            enabled = false
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
        if (!enabled) return
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
    }

    private fun onMessage(json: JsonObject) {
        when (json.get("type")?.asString) {
            "welcome" -> enabled = json.get("enabled")?.asBoolean == true
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
