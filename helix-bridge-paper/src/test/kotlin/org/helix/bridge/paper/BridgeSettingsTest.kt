package org.helix.bridge.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BridgeSettingsTest {
    @Test
    fun `settings load from environment`() {
        val settings = BridgeSettings.fromEnvironment(
            mapOf(
                "HELIX_SERVICE_ID" to "Lobby-1",
                "HELIX_CONTROL_URL" to "http://127.0.0.1:8080/",
                "HELIX_CONTROL_TOKEN" to "secret",
                "HELIX_TASK" to "Lobby",
            ),
        )

        assertEquals(BridgeSettings("Lobby-1", "http://127.0.0.1:8080", "http://127.0.0.1:8080", "secret", "Lobby"), settings)
    }

    @Test
    fun `task defaults to empty when the wrapper omits it`() {
        val settings = BridgeSettings.fromEnvironment(
            mapOf(
                "HELIX_SERVICE_ID" to "Lobby-1",
                "HELIX_CONTROL_URL" to "http://127.0.0.1:8080",
                "HELIX_CONTROL_TOKEN" to "secret",
            ),
        )

        assertEquals("", settings?.task)
    }

    @Test
    fun `missing variables disable the bridge`() {
        assertNull(BridgeSettings.fromEnvironment(emptyMap()))
        assertNull(BridgeSettings.fromEnvironment(mapOf("HELIX_SERVICE_ID" to "Lobby-1")))
    }
}
