package org.helix.addons.lobby

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult

/**
 * Lobby addon.
 *
 * Owns the single source of truth for what a lobby is: which tasks act as
 * lobbies, the hotbar layout per task, the protection rules and the server
 * selector's look. The configuration is edited in the dashboard panel, kept
 * in addon storage and republished as the `lobby.config` bridge value so
 * every lobby backend applies it live.
 *
 * The paper component reaches the node through bridge-invocable actions: it
 * reads the live joinable backends for the server selector via
 * [serversAction], and the team's `/setlobbyspawn` stores the lobby spawn via
 * `lobby.spawn.set` (checked against [ADMIN_PERMISSION] by player name, since
 * a bridge call carries no session). Everything else is dashboard/CLI only.
 */
class LobbyAddon : AddonBase() {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val compact = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private lateinit var config: LobbyConfig

    /**
     * Loads the configuration, publishes it and registers the `lobby.*`
     * actions plus the dashboard panel.
     */
    override fun enable() {
        loadMessages()
        config = load()
        publish()

        action("lobby.get", "Exports the lobby configuration as JSON (dashboard).", "lobby.get") {
            ActionResult.ok(json.encodeToString(LobbyConfig.serializer(), config))
        }
        action(
            "lobby.set",
            "Replaces the whole lobby configuration from JSON.",
            "lobby.set <json>",
        ) { invocation -> setConfig(invocation.arguments.joinToString(" ")) }
        action("lobby.show", "Summarizes the lobby configuration.", "lobby.show") {
            ActionResult.ok(
                "lobby tasks: ${config.lobbyTasks.joinToString(", ").ifBlank { "(none)" }}",
                "layouts: ${config.layouts.keys.joinToString(", ")}",
                "protection: ${describeProtection()}",
                "spawn: ${config.spawn?.let { "%.1f %.1f %.1f".format(java.util.Locale.ROOT, it.x, it.y, it.z) } ?: "(world spawn)"}",
            )
        }
        action(
            "lobby.spawn.set",
            "Stores the lobby spawn (team command /setlobbyspawn of the paper component).",
            "lobby.spawn.set <player> <json {x,y,z,yaw,pitch}>",
            bridgeInvocable = true,
            // Only lobby servers may move the spawn (fixed at enable; a changed lobby task list needs a node restart).
            bridgeTasks = config.lobbyTasks.toSet().ifEmpty { setOf(DEFAULT_LOBBY_TASK) },
        ) { invocation -> setSpawn(invocation.arguments) }
        action(
            "lobby.spawn.clear",
            "Removes the stored lobby spawn; lobbies fall back to the world's own spawn.",
            "lobby.spawn.clear",
            permission = ADMIN_PERMISSION,
        ) {
            config = config.copy(spawn = null)
            save()
            publish()
            ActionResult.ok("lobby spawn cleared")
        }
        action(
            "lobby.servers",
            "Lists the joinable backends for the server selector (paper component).",
            "lobby.servers",
            bridgeInvocable = true,
        ) { serversAction() }

        panel(
            "lobby",
            "Lobby",
            "/panel.html",
            "<path d=\"M3 21V9l9-6 9 6v12h-6v-7H9v7z\"/>",
        )
    }

    /**
     * Stores the lobby spawn sent by `/setlobbyspawn`. The bridge call carries no session, so the named player must
     * hold [ADMIN_PERMISSION] on the node and be online on the network (the action is limited to lobby servers; which
     * lobby the player stands on is not part of a bridge call).
     *
     * @param arguments `<player> <json…>`.
     * @return ok with the stored point, or an error (`usage`, `forbidden`, `invalid spawn`).
     */
    private fun setSpawn(arguments: List<String>): ActionResult {
        val player = arguments.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: return ActionResult.error("usage", "lobby.spawn.set <player> <json>")
        if (!context.hasPermission(player, ADMIN_PERMISSION)) return ActionResult.error("forbidden")
        if (context.onlinePlayers().none { it.name.equals(player, ignoreCase = true) }) {
            return ActionResult.error("forbidden", "$player is not online")
        }
        val spawn = runCatching { compact.decodeFromString(LobbySpawn.serializer(), arguments.drop(1).joinToString(" ")) }
            .getOrNull()?.takeIf { it.isValid() }
            ?: return ActionResult.error("invalid spawn")
        config = config.copy(spawn = spawn)
        save()
        publish()
        return ActionResult.ok(compact.encodeToString(LobbySpawn.serializer(), spawn))
    }

    /**
     * Validates and stores a replacement configuration, then republishes it.
     *
     * @param raw the configuration JSON.
     * @return an ok result summarizing the new config, or an error when the
     *  JSON is invalid.
     */
    private fun setConfig(raw: String): ActionResult {
        val imported = runCatching { json.decodeFromString(LobbyConfig.serializer(), raw) }.getOrNull()
            ?: return ActionResult.error("invalid lobby JSON")
        // The panel edits a copy loaded earlier: an import without a spawn keeps the one set in game
        // (clear it explicitly with lobby.spawn.clear).
        config = sanitize(imported).let { if (it.spawn == null) it.copy(spawn = config.spawn) else it }
        save()
        publish()
        return ActionResult.ok(
            "lobby updated (${config.lobbyTasks.size} lobby task(s), ${config.layouts.size} layout(s))",
        )
    }

    /**
     * Clamps values that would break the paper side: slots into 0..8, server
     * navigator modes without a task and drops empty task names.
     *
     * @param raw the imported config.
     * @return the sanitized config.
     */
    private fun sanitize(raw: LobbyConfig): LobbyConfig = raw.copy(
        spawn = raw.spawn?.takeIf { it.isValid() },
        lobbyTasks = raw.lobbyTasks.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        layouts = raw.layouts.mapValues { (_, layout) ->
            layout.copy(items = layout.items.filter { it.slot in 0..8 })
        },
        serverMenu = raw.serverMenu.copy(modes = raw.serverMenu.modes.filter { it.task.isNotBlank() }),
    )

    /**
     * Builds the server-selector list from the node's live services: every
     * running backend, optionally without the lobby tasks themselves.
     *
     * @return an ok result whose first line is the JSON [ServerEntry] list.
     */
    private fun serversAction(): ActionResult {
        val listing = context.actions.invoke(ActionInvocation("service.list"))
        val entries = listing.lines.mapNotNull(::parseServiceLine)
            .filter { !config.serverMenu.excludeLobbyTasks || !config.isLobbyTask(it.task) }
            .sortedWith(compareBy({ it.task }, { it.id }))
        return ActionResult.ok(compact.encodeToString(SERVER_LIST, entries))
    }

    /**
     * Parses one `service.list` line into a running-backend entry.
     *
     * The builtin format is `<id> [<STATE>] port=<p> players=<n>/<max>
     * executor=<e>`; only `RUNNING` services are joinable.
     *
     * @param line one output line.
     * @return the entry, or `null` when the line is not a running service.
     */
    private fun parseServiceLine(line: String): ServerEntry? {
        val id = line.substringBefore(' ').takeIf { it.isNotBlank() && it.contains('-') } ?: return null
        val state = line.substringAfter('[', "").substringBefore(']', "")
        if (state != "RUNNING") return null
        val players = line.substringAfter("players=", "").substringBefore('/', "")
        val max = line.substringAfter("players=", "").substringAfter('/', "").substringBefore(' ')
        return ServerEntry(
            id = id,
            task = id.substringBeforeLast('-'),
            players = players.toIntOrNull() ?: 0,
            maxPlayers = max.toIntOrNull() ?: 0,
        )
    }

    private fun describeProtection(): String = with(config.protection) {
        buildList {
            if (adventureMode) add("adventure")
            if (preventBlockBreak || preventBlockPlace) add("build-lock")
            if (preventDamage) add("no-damage")
            if (voidTeleport) add("void->spawn")
        }.joinToString(", ").ifBlank { "off" }
    }

    private fun publish() {
        context.publishBridgeValue("lobby.config", compact.encodeToString(LobbyConfig.serializer(), config))
    }

    private fun load(): LobbyConfig =
        context.storage().read("lobby")?.let {
            runCatching { compact.decodeFromString(LobbyConfig.serializer(), it) }.getOrNull()
        } ?: LobbyConfig()

    private fun save() {
        context.storage().write("lobby", compact.encodeToString(LobbyConfig.serializer(), config))
    }

    /** Shared constants of the lobby addon. */
    companion object {
        private val SERVER_LIST = kotlinx.serialization.builtins.ListSerializer(ServerEntry.serializer())

        /** Permission to set or clear the lobby spawn. */
        const val ADMIN_PERMISSION: String = "helix.lobby.admin"

        /** Lobby task assumed when the configuration names none (the deploy bundle's `Lobby`). */
        const val DEFAULT_LOBBY_TASK: String = "Lobby"
    }
}
