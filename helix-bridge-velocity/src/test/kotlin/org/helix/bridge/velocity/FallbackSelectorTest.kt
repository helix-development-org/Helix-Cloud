package org.helix.bridge.velocity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FallbackSelectorTest {
    private val candidates = listOf(
        FallbackCandidate("Lobby-1", players = 5, fallbackEligible = true),
        FallbackCandidate("Lobby-2", players = 2, fallbackEligible = true),
        FallbackCandidate("Game-1", players = 0, fallbackEligible = false),
    )

    @Test
    fun `selects least loaded fallback eligible server`() {
        assertEquals("Lobby-2", FallbackSelector.select(candidates))
    }

    @Test
    fun `exclusion skips the origin server`() {
        assertEquals("Lobby-1", FallbackSelector.select(candidates, exclude = "Lobby-2"))
    }

    @Test
    fun `no eligible server yields null`() {
        assertNull(FallbackSelector.select(candidates.filter { !it.fallbackEligible }))
    }

    @Test
    fun `a maintenance-flagged backend is skipped by default`() {
        val withMaintenance = listOf(
            FallbackCandidate("Lobby-1", players = 5, fallbackEligible = true),
            FallbackCandidate("Lobby-2", players = 0, fallbackEligible = true, maintenance = true),
        )

        assertEquals("Lobby-1", FallbackSelector.select(withMaintenance))
    }

    @Test
    fun `maintenance bypass still allows the flagged backend to be picked`() {
        val withMaintenance = listOf(
            FallbackCandidate("Lobby-1", players = 5, fallbackEligible = true),
            FallbackCandidate("Lobby-2", players = 0, fallbackEligible = true, maintenance = true),
        )

        assertEquals("Lobby-2", FallbackSelector.select(withMaintenance, bypassMaintenance = true))
    }

    @Test
    fun `settings load from environment`() {
        val settings = BridgeSettings.fromEnvironment(
            mapOf(
                "HELIX_SERVICE_ID" to "Proxy-1",
                "HELIX_CONTROL_URL" to "http://host.docker.internal:8080",
                "HELIX_CONTROL_TOKEN" to "secret",
            ),
        )

        assertEquals("Proxy-1", settings?.serviceId)
    }
}
