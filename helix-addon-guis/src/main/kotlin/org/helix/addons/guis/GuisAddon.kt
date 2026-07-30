package org.helix.addons.guis

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionResult

/**
 * Helix GUIs addon.
 *
 * Node-side counterpart of the shared `Helix-GUIs` Paper plugin: every
 * addon that needs an IGui-based menu registers its textures through that
 * one plugin instead of installing/configuring IGui itself, so a menu can
 * never end up half-working because one addon forgot a font namespace or a
 * texture database (the exact bug this addon replaces — see the profile,
 * guard and bettermsgs menus' history).
 */
class GuisAddon : AddonBase() {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var textures: GuiTextureStore

    /** Registers the `guis.texture.*` actions backing the shared plugin's texture database. */
    override fun enable() {
        textures = GuiTextureStore(context.storage())

        action(
            "guis.texture.list",
            "Lists every stored IGui texture definition.",
            "guis.texture.list",
            bridgeInvocable = true,
        ) {
            ActionResult.ok(json.encodeToString(textures.all()))
        }
        action(
            "guis.texture.get",
            "Reads one stored IGui texture definition.",
            "guis.texture.get <id>",
            bridgeInvocable = true,
        ) { invocation ->
            val id = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: guis.texture.get <id>")
            textures.get(id)?.let { ActionResult.ok(json.encodeToString(it)) }
                ?: ActionResult.error("no such texture: $id")
        }
        action(
            "guis.texture.put",
            "Stores (or replaces) one IGui texture definition.",
            "guis.texture.put <id> <json>",
            bridgeInvocable = true,
        ) { invocation ->
            val id = invocation.arguments.getOrNull(0)
            val recordJson = invocation.arguments.getOrNull(1)
            if (id == null || recordJson == null) {
                return@action ActionResult.error("usage: guis.texture.put <id> <json>")
            }
            val record = runCatching { json.decodeFromString<GuiTextureRecord>(recordJson) }
                .getOrElse { return@action ActionResult.error("invalid texture json: ${it.message}") }
            textures.put(record)
            ActionResult.ok("stored")
        }
        action(
            "guis.texture.remove",
            "Removes one stored IGui texture definition.",
            "guis.texture.remove <id>",
            bridgeInvocable = true,
        ) { invocation ->
            val id = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: guis.texture.remove <id>")
            if (textures.remove(id)) ActionResult.ok("removed") else ActionResult.error("no such texture: $id")
        }
    }
}
