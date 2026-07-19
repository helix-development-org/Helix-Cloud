package org.helix.addons.permissions

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionHandler
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import org.helix.api.addon.AddonContext
import org.helix.api.addon.JoinGate
import org.helix.api.addon.PermissionResolver
import org.helix.api.proxy.PermissionCheckRequest

/**
 * Fake context capturing everything the addon registers.
 */
private class FakeContext(override val dataDirectory: Path) : AddonContext {
    val handlers = mutableMapOf<String, ActionHandler>()
    val resolvers = mutableListOf<PermissionResolver>()

    override val actions: ActionInvoker = object : ActionInvoker {
        override fun invoke(invocation: ActionInvocation): ActionResult = ActionResult.ok()

        override fun descriptors() = emptyList<ActionDescriptor>()
    }

    override fun registerAction(descriptor: ActionDescriptor, handler: ActionHandler) {
        handlers[descriptor.name] = handler
    }

    override fun registerJoinGate(gate: JoinGate) {
        // not used by the permissions addon
    }

    override fun registerPermissionResolver(resolver: PermissionResolver) {
        resolvers += resolver
    }

    fun run(action: String, vararg args: String): ActionResult =
        handlers.getValue(action).execute(ActionInvocation(action, args.toList()))

    fun has(player: String, permission: String): Boolean =
        resolvers.single().has(PermissionCheckRequest(player, permission))
}

class PermissionsAddonTest {
    private val context = FakeContext(createTempDirectory("perms"))
    private val addon = PermissionsAddon().also { it.onEnable(context) }

    @Test
    fun `matcher supports wildcards and negation`() {
        assertTrue(PermissionMatcher.matches("*", "anything.at.all"))
        assertTrue(PermissionMatcher.matches("helix.command.*", "helix.command.server"))
        assertTrue(PermissionMatcher.matches("helix.command.*", "helix.command"))
        assertFalse(PermissionMatcher.matches("helix.command.*", "helix.other"))
        assertEquals(false, PermissionMatcher.decide(listOf("helix.*", "-helix.secret"), "helix.secret"))
        assertEquals(true, PermissionMatcher.decide(listOf("helix.*", "-helix.secret"), "helix.other"))
        assertEquals(null, PermissionMatcher.decide(listOf("other"), "helix.x"))
    }

    @Test
    fun `group grant flows to members through actions and resolver`() {
        context.run("perm.group.create", "admin", "weight=100")
        context.run("perm.group.grant", "admin", "helix.maintenance.bypass")
        context.run("perm.user.addgroup", "Steve", "admin")

        assertTrue(context.has("steve", "helix.maintenance.bypass"))
        assertFalse(context.has("alex", "helix.maintenance.bypass"))
        assertTrue(context.run("perm.check", "Steve", "helix.maintenance.bypass").lines.single().contains("HAS"))
    }

    @Test
    fun `default group applies to players without groups`() {
        context.run("perm.group.grant", "default", "helix.command.lobby")

        assertTrue(context.has("randomplayer", "helix.command.lobby"))
        assertFalse(context.has("randomplayer", "helix.command.admin"))
    }

    @Test
    fun `higher weight group wins conflicts`() {
        context.run("perm.group.create", "muted", "weight=50")
        context.run("perm.group.grant", "muted", "-chat.send")
        context.run("perm.group.create", "member", "weight=0")
        context.run("perm.group.grant", "member", "chat.send")
        context.run("perm.user.addgroup", "steve", "member")
        context.run("perm.user.addgroup", "steve", "muted")

        assertFalse(context.has("steve", "chat.send"))
    }

    @Test
    fun `personal permissions beat group permissions`() {
        context.run("perm.group.create", "admin", "weight=100")
        context.run("perm.group.grant", "admin", "*")
        context.run("perm.user.addgroup", "steve", "admin")
        context.run("perm.user.grant", "steve", "-helix.dangerous")

        assertFalse(context.has("steve", "helix.dangerous"))
        assertTrue(context.has("steve", "helix.anything.else"))
    }

    @Test
    fun `groups inherit parent permissions`() {
        context.run("perm.group.create", "member")
        context.run("perm.group.grant", "member", "helix.command.lobby")
        context.run("perm.group.create", "vip", "weight=10")
        context.run("perm.group.addparent", "vip", "member")
        context.run("perm.user.addgroup", "steve", "vip")

        assertTrue(context.has("steve", "helix.command.lobby"))
    }

    @Test
    fun `inheritance cycles are safe`() {
        context.run("perm.group.create", "a")
        context.run("perm.group.create", "b")
        context.run("perm.group.addparent", "a", "b")
        context.run("perm.group.addparent", "b", "a")
        context.run("perm.group.grant", "b", "x")
        context.run("perm.user.addgroup", "steve", "a")

        assertTrue(context.has("steve", "x"))
    }

    @Test
    fun `store persists across instances`() {
        val file = createTempDirectory("perms").resolve("permissions.json")
        val first = PermissionStore(file)
        first.saveGroup(PermissionGroup("vip", weight = 5, permissions = listOf("helix.vip")))
        first.saveUser(PermissionUser("steve", groups = listOf("vip")))

        val second = PermissionStore(file)

        assertTrue(second.has("steve", "helix.vip"))
        assertEquals(5, second.group("vip")?.weight)
    }

    @Test
    fun `deleting a group cleans members and parents`() {
        context.run("perm.group.create", "old")
        context.run("perm.group.create", "child")
        context.run("perm.group.addparent", "child", "old")
        context.run("perm.user.addgroup", "steve", "old")
        context.run("perm.group.delete", "old")

        assertTrue(context.run("perm.group.info", "child").lines.any { it == "parents: -" })
        assertTrue(context.run("perm.user.info", "steve").lines.any { it.contains("default") })
    }

    @Test
    fun `duplicate group creation is rejected`() {
        context.run("perm.group.create", "vip")

        assertFalse(context.run("perm.group.create", "vip").success)
    }
}
