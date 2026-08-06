package org.helix.addons.bettermsgs.paper

import org.helix.api.action.ActionInvocation
import org.helix.api.i18n.TranslationsSnapshot
import org.helix.wire.ServiceNodeApi

/**
 * Node client for the BetterMSGs paper component, over the shared
 * [ServiceNodeApi] transport — calls travel over Helix-Wire when it is up
 * and HTTP otherwise. The public shape is unchanged.
 *
 * @property controlUrl the primary control url (`helix://` or `http://`).
 * @property token per-service bearer token.
 */
class NodeClient(val controlUrl: String, token: String) {
    private val api = ServiceNodeApi(
        controlUrl,
        System.getenv("HELIX_CONTROL_HTTP_URL")?.ifBlank { null } ?: controlUrl,
        System.getenv("HELIX_SERVICE_ID").orEmpty(),
        token,
    ).also { it.start() }

    /**
     * Fetches the translations snapshot.
     *
     * @return the snapshot, or `null` when the node is unreachable.
     */
    fun translations(): TranslationsSnapshot? = api.translations()

    /**
     * Invokes a bridge-invocable node action and returns its first result line.
     *
     * @param name action name.
     * @param args action arguments.
     * @return the first line of a successful result, or `null`.
     */
    fun action(name: String, vararg args: String): String? {
        val result = api.action(ActionInvocation(name, args.toList())) ?: return null
        return if (result.success) result.lines.firstOrNull() else null
    }

    /**
     * Closes the underlying transport.
     */
    fun close() {
        api.close()
    }

    companion object {
        /**
         * Builds and starts a client from the wrapper environment.
         *
         * @return the started client, or `null` when the Helix environment is absent.
         */
        fun fromEnvironment(): NodeClient? {
            val url = System.getenv("HELIX_CONTROL_URL") ?: return null
            val token = System.getenv("HELIX_CONTROL_TOKEN") ?: return null
            return NodeClient(url, token)
        }
    }
}
