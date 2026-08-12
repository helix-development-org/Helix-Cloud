package org.helix.addons.phone.paper

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.helix.api.action.ActionInvocation
import org.helix.wire.ServiceNodeApi
import org.slf4j.LoggerFactory

/**
 * Node client for the phone paper component, over the shared
 * [ServiceNodeApi] transport. Reads the per-player app list and the
 * navigator's joinable backends; never writes.
 *
 * @param controlUrl the primary control url (`helix://` or `http://`).
 * @param token per-service bearer token.
 */
class PhoneNodeClient(controlUrl: String, token: String) {
    private val logger = LoggerFactory.getLogger(PhoneNodeClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val api = ServiceNodeApi(
        controlUrl,
        System.getenv("HELIX_CONTROL_HTTP_URL")?.ifBlank { null } ?: controlUrl,
        System.getenv("HELIX_SERVICE_ID").orEmpty(),
        token,
    ).also { it.start() }

    /**
     * Fetches the apps a player may see.
     *
     * @param player the player name.
     * @return the visible apps, or an empty list when the node is unreachable.
     */
    fun apps(player: String): List<AppView> {
        val result = api.action(ActionInvocation("phone.apps", listOf(player))) ?: return emptyList()
        if (!result.success) return emptyList()
        val raw = result.lines.firstOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(APP_LIST, raw) }
            .onFailure { logger.warn("Could not parse phone apps: {}", it.message) }
            .getOrNull() ?: emptyList()
    }

    /**
     * Fetches the joinable backends for the navigator.
     *
     * @return the entries, or an empty list when the node is unreachable.
     */
    fun servers(): List<ServerEntry> {
        val result = api.action(ActionInvocation("phone.servers")) ?: return emptyList()
        if (!result.success) return emptyList()
        val raw = result.lines.firstOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(SERVER_LIST, raw) }.getOrNull() ?: emptyList()
    }

    /** Closes the underlying transport. */
    fun close() {
        api.close()
    }

    private companion object {
        private val APP_LIST = ListSerializer(AppView.serializer())
        private val SERVER_LIST = ListSerializer(ServerEntry.serializer())
    }
}
