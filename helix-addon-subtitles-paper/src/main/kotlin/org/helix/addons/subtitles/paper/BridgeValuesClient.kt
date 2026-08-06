package org.helix.addons.subtitles.paper

import org.helix.wire.ServiceNodeApi

/**
 * Reads the node's published bridge values, scoped to this service's task,
 * over the shared [ServiceNodeApi] transport — values travel over
 * Helix-Wire when it is up and HTTP otherwise. The public shape is
 * unchanged.
 *
 * @property controlUrl the primary control url (`helix://` or `http://`).
 * @property token per-service bearer token.
 * @property serviceId this service's id.
 */
class BridgeValuesClient(
    controlUrl: String,
    token: String,
    serviceId: String,
) {
    private val api = ServiceNodeApi(
        controlUrl,
        System.getenv("HELIX_CONTROL_HTTP_URL")?.ifBlank { null } ?: controlUrl,
        serviceId,
        token,
    ).also { it.start() }

    /**
     * Fetches every currently published bridge value.
     *
     * @return key to value, or `null` if the node is unreachable.
     */
    fun fetch(): Map<String, String>? = api.bridgeValues()

    /**
     * Closes the underlying transport.
     */
    fun close() {
        api.close()
    }
}
