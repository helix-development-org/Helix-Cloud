package org.helix.node.gates

import org.helix.api.proxy.PermissionCheckRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionServiceTest {
    private fun request(name: String, node: String) = PermissionCheckRequest(name, node)

    @Test
    fun `native default decides when no addon resolver is registered`() {
        val cache = NativePermissionCache()
        cache.update("steve", listOf("helix.panel.login"))
        val service = PermissionService(PermissionResolverRegistry(), NativePermissionProvider(cache))

        assertTrue(service.check(request("steve", "helix.panel.login")))
        assertFalse(service.check(request("steve", "helix.panel.services")))
        // offline player: native abstains -> denied by default
        assertFalse(service.check(request("alex", "helix.panel.login")))
    }

    @Test
    fun `addon resolver fully overrides the native default`() {
        val cache = NativePermissionCache()
        cache.update("steve", listOf("helix.panel.login"))
        val resolvers = PermissionResolverRegistry()
        resolvers.register("perms") { req -> req.permission == "helix.panel.services" }
        val service = PermissionService(resolvers, NativePermissionProvider(cache))

        // granted by the addon
        assertTrue(service.check(request("steve", "helix.panel.services")))
        // natively granted, but the addon governs now -> denied
        assertFalse(service.check(request("steve", "helix.panel.login")))
    }
}
