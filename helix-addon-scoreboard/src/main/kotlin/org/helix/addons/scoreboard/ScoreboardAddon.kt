package org.helix.addons.scoreboard

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionResult

/**
 * Sidebar scoreboard addon.
 *
 * Stores one [BoardConfig] per task plus a shared [DEFAULT_KEY] board and
 * publishes the whole task→config map as the single `scoreboard.config`
 * bridge value. The paper bridge picks its own task's board (falling back to
 * [DEFAULT_KEY]) and renders it as a Bukkit sidebar. The map is republished on
 * every change so running services pick edits up on their next poll.
 */
class ScoreboardAddon : AddonBase() {
    private val json = Json { prettyPrint = false; encodeDefaults = true; ignoreUnknownKeys = true }
    private val boardSerializer = BoardConfig.serializer()
    private val mapSerializer = MapSerializer(String.serializer(), boardSerializer)
    private val boards = linkedMapOf<String, BoardConfig>()

    /**
     * Loads the boards, publishes them and registers the panel actions.
     */
    override fun enable() {
        boards.clear()
        boards.putAll(load())
        boards.putIfAbsent(DEFAULT_KEY, BoardConfig())
        publish()
        action("scoreboard.get", "Returns all scoreboards as JSON (dashboard).", "scoreboard.get") {
            ActionResult.ok(json.encodeToString(mapSerializer, boards))
        }
        action(
            "scoreboard.set",
            "Sets or replaces a task's board from JSON (max $MAX_LINES lines).",
            "scoreboard.set <task> <json>",
        ) { invocation ->
            val task = invocation.arguments.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: return@action ActionResult.error("usage: scoreboard.set <task> <json>")
            val raw = invocation.arguments.drop(1).joinToString(" ")
            val parsed = runCatching { json.decodeFromString(boardSerializer, raw) }.getOrNull()
                ?: return@action ActionResult.error("invalid board JSON")
            if (parsed.lines.size > MAX_LINES) {
                return@action ActionResult.error("too many lines (max $MAX_LINES)")
            }
            boards[task] = parsed.copy(updateIntervalTicks = parsed.updateIntervalTicks.coerceAtLeast(MIN_INTERVAL_TICKS))
            save()
            publish()
            ActionResult.ok("board for '$task' saved (${parsed.lines.size} lines)")
        }
        action(
            "scoreboard.setline",
            "Sets a single line of a task's board (index appends when at the end).",
            "scoreboard.setline <task> <index> <text...>",
        ) { invocation ->
            val task = invocation.arguments.getOrNull(0)?.takeIf { it.isNotBlank() }
                ?: return@action ActionResult.error("usage: scoreboard.setline <task> <index> <text...>")
            val index = invocation.arguments.getOrNull(1)?.toIntOrNull()
                ?: return@action ActionResult.error("index must be a number")
            val text = invocation.arguments.drop(2).joinToString(" ")
            val board = boards[task] ?: boards[DEFAULT_KEY] ?: BoardConfig()
            val lines = board.lines.toMutableList()
            when {
                index < 0 -> return@action ActionResult.error("index must be >= 0")
                index < lines.size -> lines[index] = text
                index == lines.size -> lines.add(text)
                else -> return@action ActionResult.error("index out of range (0..${lines.size})")
            }
            if (lines.size > MAX_LINES) {
                return@action ActionResult.error("too many lines (max $MAX_LINES)")
            }
            boards[task] = board.copy(lines = lines)
            save()
            publish()
            ActionResult.ok("line $index of '$task' updated")
        }
        action(
            "scoreboard.reset",
            "Removes a task's board so it falls back to the default.",
            "scoreboard.reset <task>",
        ) { invocation ->
            val task = invocation.arguments.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: return@action ActionResult.error("usage: scoreboard.reset <task>")
            if (task == DEFAULT_KEY) {
                boards[DEFAULT_KEY] = BoardConfig()
            } else {
                boards.remove(task)
            }
            save()
            publish()
            ActionResult.ok("board for '$task' reset")
        }
        action(
            "scoreboard.toggle",
            "Enables or disables a task's board.",
            "scoreboard.toggle <task> <on|off>",
        ) { invocation ->
            val task = invocation.arguments.getOrNull(0)?.takeIf { it.isNotBlank() }
                ?: return@action ActionResult.error("usage: scoreboard.toggle <task> <on|off>")
            val enabled = when (invocation.arguments.getOrNull(1)?.lowercase()) {
                "on", "true", "enable", "enabled" -> true
                "off", "false", "disable", "disabled" -> false
                else -> return@action ActionResult.error("usage: scoreboard.toggle <task> <on|off>")
            }
            val board = boards[task] ?: boards[DEFAULT_KEY] ?: BoardConfig()
            boards[task] = board.copy(enabled = enabled)
            save()
            publish()
            ActionResult.ok("board for '$task' ${if (enabled) "enabled" else "disabled"}")
        }
        panel(
            "scoreboard",
            "Scoreboard",
            "/panel.html",
            "<rect x=\"3\" y=\"4\" width=\"18\" height=\"16\" rx=\"2\"/><path d=\"M7 8h10M7 12h7M7 16h4\"/>",
        )
    }

    private fun publish() {
        context.publishBridgeValue("scoreboard.config", json.encodeToString(mapSerializer, boards))
    }

    private fun load(): Map<String, BoardConfig> =
        context.storage().read(STORAGE_KEY)?.let {
            runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull()
        } ?: mapOf(DEFAULT_KEY to BoardConfig())

    private fun save() {
        context.storage().write(STORAGE_KEY, json.encodeToString(mapSerializer, boards))
    }

    /** Constants shared by the addon and its tests. */
    companion object {
        /** Maximum number of sidebar lines per board (Bukkit sidebar limit). */
        const val MAX_LINES = 15

        /** Lowest allowed refresh interval, guarding against client spam. */
        const val MIN_INTERVAL_TICKS = 1

        /** Map key of the shared board used when a task has no board. */
        const val DEFAULT_KEY = "default"

        /** Storage document holding the serialized task→board map. */
        const val STORAGE_KEY = "boards"
    }
}
