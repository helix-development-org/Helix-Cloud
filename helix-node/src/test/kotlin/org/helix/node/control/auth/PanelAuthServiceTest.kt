package org.helix.node.control.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.api.player.PlayerEvent
import org.helix.node.gates.NativePermissionCache
import org.helix.node.gates.NativePermissionProvider
import org.helix.node.gates.PermissionResolverRegistry
import org.helix.node.gates.PermissionService
import org.helix.node.players.PlayerRegistry

class PanelAuthServiceTest {
    private var now = 1_000L
    private var delivered: String? = null

    private fun service(cache: NativePermissionCache, players: PlayerRegistry): PanelAuthService =
        PanelAuthService(
            adminToken = "admin-token",
            loginPermission = "helix.panel.login",
            loginMessage = "code:{code}",
            codeTtlMs = 300_000,
            sessionTtlMs = 3_600_000,
            players = players,
            permissions = PermissionService(PermissionResolverRegistry(), NativePermissionProvider(cache)),
            deliver = { _, text -> delivered = text; true },
            clock = { now },
        )

    @Test
    fun `full login flow issues a session scoped to the player permissions`() {
        val players = PlayerRegistry().apply { handle(PlayerEvent("join", "Steve", "u")) }
        val cache = NativePermissionCache().apply {
            update("steve", listOf("helix.panel.login", "helix.panel.services"))
        }
        val auth = service(cache, players)

        val challenge = auth.requestCode("steve")
        assertTrue(challenge.delivered)
        val code = delivered!!.removePrefix("code:")

        val session = auth.verify("STEVE", code)
        assertEquals("Steve", session.identity.name)
        assertFalse(session.identity.admin)
        assertTrue(session.identity.views.contains("services"))
        assertFalse(session.identity.views.contains("tasks"))

        val principal = auth.authenticate(session.token)
        assertEquals("Steve", principal?.name)
        assertEquals(false, principal?.admin)
    }

    @Test
    fun `admin token authenticates with full access`() {
        val auth = service(NativePermissionCache(), PlayerRegistry())
        val principal = auth.authenticate("admin-token")
        assertTrue(principal!!.admin)
        assertTrue(auth.grants(principal, "helix.panel.anything"))
        assertNull(auth.authenticate("wrong"))
    }

    @Test
    fun `request-code rejects offline players and players without access`() {
        val players = PlayerRegistry().apply {
            handle(PlayerEvent("join", "Steve", "u"))
            handle(PlayerEvent("join", "Griefer", "g"))
        }
        val cache = NativePermissionCache().apply { update("steve", listOf("helix.panel.login")) }
        val auth = service(cache, players)

        assertFailsWith<IllegalArgumentException> { auth.requestCode("Alex") } // offline
        assertFailsWith<IllegalArgumentException> { auth.requestCode("Griefer") } // no permission
    }

    @Test
    fun `verify rejects a wrong code`() {
        val players = PlayerRegistry().apply { handle(PlayerEvent("join", "Steve", "u")) }
        val cache = NativePermissionCache().apply { update("steve", listOf("helix.panel.login")) }
        val auth = service(cache, players)

        auth.requestCode("steve")
        assertFailsWith<IllegalArgumentException> { auth.verify("steve", "000000-wrong") }
    }
}
