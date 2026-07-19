package org.helix.node.players

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.api.action.PlayerCommandRequest
import org.helix.api.addon.PlayerListener
import org.helix.api.display.DisplayProfile
import org.helix.api.player.OnlinePlayer
import org.helix.api.player.PlayerEvent
import org.helix.node.actions.ActionRegistry
import org.helix.node.actions.PlayerCommandService
import org.helix.node.display.BridgeValueStore
import org.helix.node.display.DisplayResolverRegistry
import org.helix.node.gates.PermissionResolverRegistry

class PlayerPlatformTest {
    private val registry = PlayerRegistry(clock = { 42L })

    @Test
    fun `join and leave maintain the online list and notify listeners`() {
        val joined = mutableListOf<OnlinePlayer>()
        val left = mutableListOf<OnlinePlayer>()
        registry.register(
            "test",
            object : PlayerListener {
                override fun onJoin(player: OnlinePlayer) {
                    joined += player
                }

                override fun onLeave(player: OnlinePlayer) {
                    left += player
                }
            },
        )

        assertTrue(registry.handle(PlayerEvent("join", "Steve", "u1", "Proxy-1")))
        assertEquals("Steve", registry.find("steve")?.name)
        assertEquals(42L, registry.online().single().joinedAtEpochMs)

        assertTrue(registry.handle(PlayerEvent("leave", "STEVE", proxyServiceId = "Proxy-1")))
        assertTrue(registry.online().isEmpty())
        assertEquals(listOf("Steve"), joined.map { it.name })
        assertEquals(listOf("Steve"), left.map { it.name })
        assertFalse(registry.handle(PlayerEvent("teleport", "Steve")))
    }

    @Test
    fun `terminated proxy drops its players`() {
        registry.handle(PlayerEvent("join", "Steve", proxyServiceId = "Proxy-1"))
        registry.handle(PlayerEvent("join", "Alex", proxyServiceId = "Proxy-2"))

        registry.dropProxy("Proxy-1")

        assertEquals(listOf("Alex"), registry.online().map { it.name })
    }

    @Test
    fun `player command service enforces permission and player-first convention`() {
        val actions = ActionRegistry()
        val permissions = PermissionResolverRegistry()
        permissions.register("perms") { request -> request.name == "steve" && request.permission == "mod" }
        var received: List<String> = emptyList()
        actions.register(
            ActionDescriptor("kick", "kicks", "kick <player>", playerCommand = true, permission = "mod"),
        ) { invocation ->
            received = invocation.arguments
            ActionResult.ok("done")
        }
        actions.register(ActionDescriptor("secret.internal", "internal", "secret.internal"))
        val service = PlayerCommandService(actions, permissions)

        assertEquals(listOf("kick"), service.commands().map { it.name })
        assertTrue(service.execute(PlayerCommandRequest("steve", "kick", listOf("griefer"))).success)
        assertEquals(listOf("steve", "griefer"), received)
        assertFalse(service.execute(PlayerCommandRequest("alex", "kick", listOf("x"))).success)
        assertFalse(service.execute(PlayerCommandRequest("steve", "secret.internal")).success)
    }

    @Test
    fun `display registry returns first non-null profile and bridge values track owners`() {
        val displays = DisplayResolverRegistry()
        displays.register("a") { null }
        displays.register("b") { name -> if (name == "steve") DisplayProfile(prefix = "&cAdmin ") else null }

        assertEquals("&cAdmin ", displays.resolve("steve").prefix)
        assertEquals(DisplayProfile(), displays.resolve("alex"))

        val values = BridgeValueStore()
        values.publish("tablist", "tablist.header", "&6Helix")
        values.publish("chat", "chat.format", "{name}: {message}")
        assertEquals("&6Helix", values.all()["tablist.header"])

        values.unpublishOwner("tablist")
        assertNull(values.all()["tablist.header"])
        assertEquals("{name}: {message}", values.all()["chat.format"])
    }
}
