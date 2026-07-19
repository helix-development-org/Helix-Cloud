package org.helix.bridge.velocity

/**
 * Connection settings the wrapper exports to the bridge.
 *
 * @property serviceId id of the proxy service.
 * @property controlUrl base URL of the node control API.
 * @property token bearer token for the control API.
 */
data class BridgeSettings(
    val serviceId: String,
    val controlUrl: String,
    val token: String,
) {
    companion object {
        /**
         * Reads the settings from environment variables.
         *
         * @param env environment map, injectable for tests.
         * @return the settings, or `null` when the proxy does not run under
         *   a Helix wrapper.
         */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): BridgeSettings? {
            val serviceId = env["HELIX_SERVICE_ID"] ?: return null
            val controlUrl = env["HELIX_CONTROL_URL"] ?: return null
            val token = env["HELIX_CONTROL_TOKEN"] ?: return null
            return BridgeSettings(serviceId, controlUrl.trimEnd('/'), token)
        }
    }
}
