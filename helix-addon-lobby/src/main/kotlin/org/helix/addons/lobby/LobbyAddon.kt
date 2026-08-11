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
 * The paper component reaches the node through two bridge-invocable actions:
 * it never writes config (that is dashboard/CLI only), it only reads the
 * live joinable backends for the server selector via [serversAction].
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
            )
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
     * Validates and stores a replacement configuration, then republishes it.
     *
     * @param raw the configuration JSON.
     * @return an ok result summarizing the new config, or an error when the
     *  JSON is invalid.
     */
    private fun setConfig(raw: String): ActionResult {
        val imported = runCatching { json.decodeFromString(LobbyConfig.serializer(), raw) }.getOrNull()
            ?: return ActionResult.error("invalid lobby JSON")
        config = sanitize(imported)
        save()
        publish()
        return ActionResult.ok(
            "lobby updated (${config.lobbyTasks.size} lobby task(s), ${config.layouts.size} layout(s))",
        )
    }

    /**
     * Clamps values that would break the paper side: slots into 0..8, server
     * menu rows into 1..6 and drops empty task names.
     *
     * @param raw the imported config.
     * @return the sanitized config.
     */
    private fun sanitize(raw: LobbyConfig): LobbyConfig = raw.copy(
        lobbyTasks = raw.lobbyTasks.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        layouts = raw.layouts.mapValues { (_, layout) ->
            layout.copy(items = layout.items.filter { it.slot in 0..8 })
        },
        serverMenu = raw.serverMenu.copy(rows = raw.serverMenu.rows.coerceIn(1, 6)),
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

    private companion object {
        private val SERVER_LIST = kotlinx.serialization.builtins.ListSerializer(ServerEntry.serializer())
    }
}
