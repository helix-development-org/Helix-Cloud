package org.helix.addons.npc

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult

/**
 * Network-wide NPC backend addon ("Helix-NPC").
 *
 * Persists [NpcDef] definitions in the node's document storage and exposes
 * the `npc.*` control-API actions consumed by the bundled Helix-NPC Paper
 * component. No NPC is spawned here — the node only owns the definitions;
 * every Paper server running a task fetches the NPCs for that task through
 * [list] and renders them with the INpc framework.
 */
class NpcAddon : AddonBase() {
    private lateinit var store: NpcStore
    private val json = Json { ignoreUnknownKeys = true }

    /** Registers the `npc.*` control-API actions. */
    override fun enable() {
        store = NpcStore(context.storage())
        action(
            "npc.save",
            "Inserts or replaces an NPC definition from its JSON payload.",
            "npc.save <json>",
        ) { invocation -> save(invocation) }
        action(
            "npc.delete",
            "Removes an NPC definition by id.",
            "npc.delete <id>",
        ) { invocation -> delete(invocation) }
        action(
            "npc.list",
            "Lists NPC definitions as a JSON array, optionally scoped to a task.",
            "npc.list [task]",
        ) { invocation -> list(invocation) }
        action(
            "npc.get",
            "Reads a single NPC definition as JSON.",
            "npc.get <id>",
        ) { invocation -> get(invocation) }
    }

    private fun save(invocation: ActionInvocation): ActionResult {
        // The payload may have been split on spaces by the CLI — rejoin it.
        val payload = invocation.arguments.joinToString(" ").trim()
        if (payload.isEmpty()) {
            return ActionResult.error("usage: npc.save <json>")
        }
        val parsed = runCatching { json.decodeFromString<NpcDef>(payload) }.getOrNull()
            ?: return ActionResult.error("invalid npc json")
        val normalized = runCatching { validate(parsed) }
            .getOrElse { return ActionResult.error(it.message ?: "invalid npc definition") }
        return runCatching { store.upsert(normalized) }
            .fold(
                onSuccess = { ActionResult.ok(json.encodeToString(NpcAck(ok = true))) },
                onFailure = { ActionResult.error(it.message ?: "could not save npc") },
            )
    }

    private fun delete(invocation: ActionInvocation): ActionResult {
        val id = invocation.arguments.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: return ActionResult.error("usage: npc.delete <id>")
        val removed = store.delete(id)
        return ActionResult.ok(json.encodeToString(NpcAck(ok = true, removed = removed)))
    }

    private fun list(invocation: ActionInvocation): ActionResult {
        val task = invocation.arguments.getOrNull(0)?.takeIf { it.isNotBlank() }
        return ActionResult.ok(json.encodeToString(store.list(task)))
    }

    private fun get(invocation: ActionInvocation): ActionResult {
        val id = invocation.arguments.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: return ActionResult.error("usage: npc.get <id>")
        val def = store.get(id) ?: return ActionResult.error("no such npc: $id")
        return ActionResult.ok(json.encodeToString(def))
    }

    /**
     * Validates and normalizes an incoming definition, throwing
     * [IllegalArgumentException] with a caller-facing message on any breach.
     */
    private fun validate(def: NpcDef): NpcDef {
        val id = def.id.trim().lowercase()
        require(id.isNotBlank()) { "npc id must not be blank" }
        require(id.length <= MAX_ID) { "npc id too long (max $MAX_ID)" }
        require(id.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
            "npc id may only contain letters, digits, '_', '-' and '.'"
        }
        val task = def.task.trim().ifEmpty { "*" }
        require(task.length <= MAX_TASK) { "task name too long (max $MAX_TASK)" }
        val world = def.world.trim()
        require(world.isNotBlank()) { "world must not be blank" }
        require(world.length <= MAX_WORLD) { "world name too long (max $MAX_WORLD)" }
        require(def.x.isFinite() && def.y.isFinite() && def.z.isFinite()) { "coordinates must be finite" }
        require(def.yaw.isFinite() && def.pitch.isFinite()) { "rotation must be finite" }
        val skin = def.skin.trim().ifEmpty { "self" }
        require(skin.length <= MAX_SKIN) { "skin too long (max $MAX_SKIN)" }
        require(def.hologramLines.size <= MAX_HOLOGRAM_LINES) {
            "too many hologram lines (max $MAX_HOLOGRAM_LINES)"
        }
        require(def.hologramLines.all { it.length <= MAX_LINE }) {
            "hologram line too long (max $MAX_LINE)"
        }
        val lookMode = def.lookMode.trim().lowercase().ifEmpty { "none" }
        require(lookMode in LOOK_MODES) { "lookMode must be one of $LOOK_MODES" }
        val interact = def.interactAction?.trim()?.takeIf { it.isNotEmpty() }
        require(interact == null || interact.length <= MAX_INTERACT) {
            "interactAction too long (max $MAX_INTERACT)"
        }
        return def.copy(
            id = id,
            task = task,
            world = world,
            skin = skin,
            lookMode = lookMode,
            interactAction = interact,
        )
    }

    private companion object {
        /** Maximum NPC id length. */
        const val MAX_ID = 48

        /** Maximum task name length. */
        const val MAX_TASK = 48

        /** Maximum world name length. */
        const val MAX_WORLD = 64

        /** Maximum skin string length. */
        const val MAX_SKIN = 32

        /** Maximum number of hologram lines. */
        const val MAX_HOLOGRAM_LINES = 10

        /** Maximum length of a single hologram line. */
        const val MAX_LINE = 128

        /** Maximum interact-action length. */
        const val MAX_INTERACT = 128

        /** Accepted head-behaviour modes. */
        val LOOK_MODES = setOf("none", "nearest", "player")
    }
}
