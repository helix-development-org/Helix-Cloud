package org.helix.addons.profile.paper

import kotlinx.serialization.json.Json
import org.helix.api.action.ActionInvocation
import org.helix.api.addon.ProfileView
import org.helix.wire.ServiceNodeApi
import org.slf4j.LoggerFactory

/**
 * Node-action client for the profile paper component, over the shared
 * [ServiceNodeApi] transport — calls travel over Helix-Wire when it is up
 * and HTTP otherwise. The public shape is unchanged.
 *
 * @property controlUrl the primary control url (`helix://` or `http://`).
 * @property token per-service bearer token.
 */
class ProfileNodeClient(controlUrl: String, token: String) {
    private val logger = LoggerFactory.getLogger(ProfileNodeClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val api = ServiceNodeApi(
        controlUrl,
        System.getenv("HELIX_CONTROL_HTTP_URL")?.ifBlank { null } ?: controlUrl,
        System.getenv("HELIX_SERVICE_ID").orEmpty(),
        token,
    ).also { it.start() }

    /**
     * Fetches a player's full profile view.
     *
     * @param player player name.
     * @return the parsed view, or `null` if the node is unreachable or the
     *  action failed.
     */
    fun view(player: String): ProfileView? {
        val result = api.action(ActionInvocation("profile.view", listOf(player))) ?: return null
        if (!result.success) return null
        return result.lines.firstOrNull()?.let {
            runCatching { json.decodeFromString<ProfileView>(it) }
                .onFailure { e -> logger.warn("Could not parse profile view: {}", e.message) }
                .getOrNull()
        }
    }

    /**
     * Sets the executing player's own value for a setting.
     *
     * @param player player name.
     * @param owner the addon id that registered the setting.
     * @param key the setting's key.
     * @param value the chosen value.
     * @return `null` on success, or a player-facing rejection reason.
     */
    fun set(player: String, owner: String, key: String, value: String): String? {
        val result = api.action(ActionInvocation("profile.setting.set", listOf(player, owner, key, value)))
            ?: return "the node is unreachable"
        return if (result.success) null else result.lines.firstOrNull() ?: "rejected"
    }

    /**
     * Closes the underlying transport.
     */
    fun close() {
        api.close()
    }
}
