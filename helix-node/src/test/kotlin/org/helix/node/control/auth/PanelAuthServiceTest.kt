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

    private fun service(
        cache: NativePermissionCache,
        players: PlayerRegistry,
        idleTtlMs: Long = 3_600_000,
    ): PanelAuthService =
        PanelAuthService(
            adminToken = "admin-token",
            loginPermission = "helix.panel.login",
            loginMessage = "code:{code}",
            codeTtlMs = 300_000,
            sessionTtlMs = 3_600_000,
            idleTtlMs = idleTtlMs,
            players = players,
            permissions = PermissionService(PermissionResolverRegistry(), NativePermissionProvider(cache)),
            deliver = { _, text -> delivered = text; true },
            clock = { now },
        )

    private fun login(auth: PanelAuthService, name: String): String {
        auth.requestCode(name)
        val code = delivered!!.removePrefix("code:")
        return auth.verify(name, code).token
    }

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

    @Test
    fun `offline and no-access denials are indistinguishable`() {
        val players = PlayerRegistry().apply {
            handle(PlayerEvent("join", "Steve", "u"))
            handle(PlayerEvent("join", "Griefer", "g"))
        }
        val cache = NativePermissionCache().apply { update("steve", listOf("helix.panel.login")) }
        val auth = service(cache, players)

        val offline = assertFailsWith<IllegalArgumentException> { auth.requestCode("Alex") }
        val noAccess = assertFailsWith<IllegalArgumentException> { auth.requestCode("Griefer") }

        assertEquals(offline.message, noAccess.message)
    }

    @Test
    fun `a session for a player holding helix-admin is treated as full admin`() {
        val players = PlayerRegistry().apply { handle(PlayerEvent("join", "Steve", "u")) }
        val cache = NativePermissionCache().apply {
            update("steve", listOf("helix.panel.login", "helix.admin"))
        }
        val auth = service(cache, players)

        val token = login(auth, "steve")

        val principal = auth.authenticate(token)!!
        assertTrue(principal.admin)
        assertTrue(auth.grants(principal, "helix.panel.anything"))
    }

    @Test
    fun `demoting a player revokes panel access on the very next request`() {
        val players = PlayerRegistry().apply { handle(PlayerEvent("join", "Steve", "u")) }
        val cache = NativePermissionCache().apply { update("steve", listOf("helix.panel.login")) }
        val auth = service(cache, players)
        val token = login(auth, "steve")
        assertTrue(auth.authenticate(token) != null)

        // the player is demoted (loses the login permission) without the session expiring
        cache.update("steve", emptyList())

        assertNull(auth.authenticate(token))
    }

    @Test
    fun `a session idle past the idle timeout is rejected even before the absolute TTL`() {
        val players = PlayerRegistry().apply { handle(PlayerEvent("join", "Steve", "u")) }
        val cache = NativePermissionCache().apply { update("steve", listOf("helix.panel.login")) }
        val auth = service(cache, players, idleTtlMs = 60_000)
        val token = login(auth, "steve")

        now += 30_000
        assertTrue(auth.authenticate(token) != null) // still active, refreshes the idle clock

        now += 60_000
        assertNull(auth.authenticate(token)) // idle for 60s straight -> expired
    }

    @Test
    fun `an admin can revoke a player's active sessions`() {
        val players = PlayerRegistry().apply { handle(PlayerEvent("join", "Steve", "u")) }
        val cache = NativePermissionCache().apply { update("steve", listOf("helix.panel.login")) }
        val auth = service(cache, players)
        val token = login(auth, "steve")
        assertTrue(auth.authenticate(token) != null)

        val revoked = auth.revokeSessions("STEVE")

        assertEquals(1, revoked)
        assertNull(auth.authenticate(token))
    }
}
