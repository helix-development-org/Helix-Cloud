package org.helix.node.control.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServiceTokenRegistryTest {
    private val registry = ServiceTokenRegistry()

    @Test
    fun `mint scopes a token to its service and invalidates the previous one`() {
        val first = registry.mint("Lobby-1")
        assertEquals("Lobby-1", registry.serviceIdFor(first))

        val second = registry.mint("Lobby-1")
        assertNull(registry.serviceIdFor(first))
        assertEquals("Lobby-1", registry.serviceIdFor(second))
    }

    @Test
    fun `restore re-registers a persisted token after a node restart`() {
        // the surviving process still presents the token from its original
        // environment; a fresh registry must accept exactly that token
        registry.restore("Lobby-1", "persisted-token")

        assertEquals("Lobby-1", registry.serviceIdFor("persisted-token"))
    }

    @Test
    fun `restore replaces any token currently held by the service id`() {
        val minted = registry.mint("Lobby-1")

        registry.restore("Lobby-1", "persisted-token")

        assertNull(registry.serviceIdFor(minted))
        assertEquals("Lobby-1", registry.serviceIdFor("persisted-token"))
    }

    @Test
    fun `revoke drops the token`() {
        registry.restore("Lobby-1", "persisted-token")

        registry.revoke("Lobby-1")

        assertNull(registry.serviceIdFor("persisted-token"))
    }
}
