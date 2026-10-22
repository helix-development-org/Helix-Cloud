package org.helix.addons.lobby.paper

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.helix.api.action.ActionInvocation
import org.helix.wire.ServiceNodeApi
import org.slf4j.LoggerFactory

/**
 * Node client for the lobby paper component, over the shared
 * [ServiceNodeApi] transport (Helix-Wire when up, HTTP otherwise).
 *
 * It reads the live configuration from the `lobby.config` bridge value the
 * node publishes and the server selector's list from the bridge-invocable
 * `lobby.servers` action. The only write is the team's `/setlobbyspawn`
 * ([saveSpawn]); everything else in the config is the dashboard's job.
 *
 * @param controlUrl the primary control url (`helix://` or `http://`).
 * @param token per-service bearer token.
 */
class LobbyNodeClient(controlUrl: String, token: String) {
    private val logger = LoggerFactory.getLogger(LobbyNodeClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val api = ServiceNodeApi(
        controlUrl,
        System.getenv("HELIX_CONTROL_HTTP_URL")?.ifBlank { null } ?: controlUrl,
        System.getenv("HELIX_SERVICE_ID").orEmpty(),
        token,
    ).also { it.start() }

    /**
     * Fetches the current lobby configuration from this service's bridge
     * values.
     *
     * @return the parsed configuration, or `null` when the node is
     *  unreachable or has not published it yet.
     */
    fun config(): LobbyConfig? {
        val raw = api.bridgeValues()?.get("lobby.config") ?: return null
        return runCatching { json.decodeFromString(LobbyConfig.serializer(), raw) }
            .onFailure { logger.warn("Could not parse lobby config: {}", it.message) }
            .getOrNull()
    }

    /**
     * Fetches the joinable backends for the server selector.
     *
     * @return the entries, or an empty list when the node is unreachable.
     */
    fun servers(): List<ServerEntry> {
        val result = api.action(ActionInvocation("lobby.servers")) ?: return emptyList()
        if (!result.success) return emptyList()
        val raw = result.lines.firstOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(SERVER_LIST, raw) }.getOrNull() ?: emptyList()
    }

    /**
     * Stores the lobby spawn on the node (`lobby.spawn.set`), on behalf of a team member.
     *
     * @param player the player who ran `/setlobbyspawn` (the node checks `helix.lobby.admin` by name).
     * @param spawn the new spawn point.
     * @return `null` on success, otherwise a short reason (node error line or `unreachable`).
     */
    fun saveSpawn(player: String, spawn: LobbySpawn): String? {
        val payload = json.encodeToString(LobbySpawn.serializer(), spawn)
        val result = api.action(ActionInvocation("lobby.spawn.set", listOf(player, payload))) ?: return "unreachable"
        return if (result.success) null else result.lines.firstOrNull() ?: "error"
    }

    /** Closes the underlying transport. */
    fun close() {
        api.close()
    }

    private companion object {
        private val SERVER_LIST = ListSerializer(ServerEntry.serializer())
    }
}
