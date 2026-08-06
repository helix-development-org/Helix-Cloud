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
import org.helix.api.addon.PlayerDataProvider
import org.helix.api.proxy.PermissionCheckRequest

/**
 * Fake context capturing everything the addon registers.
 */
private class FakeContext(override val dataDirectory: Path) : AddonContext {
    val handlers = mutableMapOf<String, ActionHandler>()
    val resolvers = mutableListOf<PermissionResolver>()
    val playerDataProviders = mutableListOf<PlayerDataProvider>()

    /** Simulated identity registry: lowercase name to uuid. */
    val uuidsByName = mutableMapOf<String, String>()

    override fun resolvePlayerUuid(name: String): String? = uuidsByName[name.lowercase()]

    /** Simulates a join, recording which uuid currently owns a name. */
    fun recordJoin(name: String, uuid: String) {
        uuidsByName[name.lowercase()] = uuid
    }

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

    override fun registerDisplayResolver(resolver: org.helix.api.addon.DisplayResolver) {
        displayResolvers += resolver
    }

    val displayResolvers = mutableListOf<org.helix.api.addon.DisplayResolver>()

    override fun registerPlayerDataProvider(provider: PlayerDataProvider) {
        playerDataProviders += provider
    }

    fun run(action: String, vararg args: String): ActionResult =
        handlers.getValue(action).execute(ActionInvocation(action, args.toList()))

    fun has(player: String, permission: String, uuid: String? = null): Boolean =
        resolvers.single().has(PermissionCheckRequest(player, permission, uuid))

    fun display(player: String) = displayResolvers.single().resolve(player)
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
    fun `the in-game permissions command prefixes its delegated output`() {
        context.run("perm.group.create", "admin", "weight=100")

        val result = context.run("permissions", "Steve", "group", "list")

        assertTrue(result.success)
        assertTrue(result.lines.all { it.startsWith("{prefix} ") }, result.lines.toString())
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
    fun `group prefix is display state independent of permission nodes`() {
        context.run("perm.group.create", "admin", "weight=100")
        context.run("perm.group.prefix", "admin", "&cAdmin")
        context.run("perm.group.create", "member", "weight=0")
        context.run("perm.group.prefix", "member", "&7Member")
        // a * grant on the low-weight group must not change anyone's display
        context.run("perm.group.grant", "member", "*")
        context.run("perm.user.addgroup", "steve", "member")
        context.run("perm.user.addgroup", "steve", "admin")
        context.run("perm.user.addgroup", "alex", "member")

        assertEquals("&cAdmin ", context.display("steve")?.prefix, "highest-weight group wins the prefix")
        assertEquals("&7Member ", context.display("alex")?.prefix)
    }

    @Test
    fun `default group prefix applies and parents are inherited`() {
        context.run("perm.group.prefix", "default", "&7Spieler")
        assertEquals("&7Spieler ", context.display("random")?.prefix)

        context.run("perm.group.create", "vipplus", "weight=20")
        context.run("perm.group.create", "vip", "weight=10")
        context.run("perm.group.prefix", "vip", "&6VIP")
        context.run("perm.group.color", "vip", "&6")
        context.run("perm.group.addparent", "vipplus", "vip")
        context.run("perm.user.addgroup", "steve", "vipplus")

        assertEquals("&6VIP ", context.display("steve")?.prefix, "prefix inherits through parents")
        assertEquals("&6", context.display("steve")?.color)

        context.run("perm.group.prefix", "vip")
        context.run("perm.group.color", "vip")
        assertEquals("&7Spieler ", context.display("steve")?.prefix, "cleared prefix falls back")
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
        val storage = org.helix.api.storage.InMemoryAddonStorage()
        val first = PermissionStore(storage)
        first.saveGroup(PermissionGroup("vip", weight = 5, permissions = listOf("helix.vip")))
        first.saveUser(PermissionUser("steve", groups = listOf("vip")))

        val second = PermissionStore(storage)

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

    @Test
    fun `a freed and recycled name does not inherit the previous owner's permissions`() {
        context.recordJoin("Steve", "uuid-1")
        context.run("perm.group.create", "admin", "weight=100")
        context.run("perm.group.grant", "admin", "helix.dangerous")
        context.run("perm.user.addgroup", "Steve", "admin")
        assertTrue(context.has("steve", "helix.dangerous", uuid = "uuid-1"))

        // "steve" is freed and Mojang reassigns it; the new account has a different uuid and
        // must not see any of the permissions granted to the previous owner of the name
        assertFalse(context.has("steve", "helix.dangerous", uuid = "uuid-2"))
    }

    @Test
    fun `a permission granted before the uuid is known migrates on first join`() {
        context.run("perm.group.create", "admin", "weight=100")
        context.run("perm.group.grant", "admin", "helix.dangerous")
        context.run("perm.user.addgroup", "Offline", "admin")

        // the grant was recorded name-only (the player was never seen); once they do join
        // and their uuid becomes known, the grant is still theirs
        assertTrue(context.has("Offline", "helix.dangerous", uuid = "uuid-9"))

        // and it stays theirs across a rename, since it is now keyed on the uuid
        assertTrue(context.has("Renamed", "helix.dangerous", uuid = "uuid-9"))
    }

    @Test
    fun `player-data provider exports and clears a user's grants`() {
        val provider = context.playerDataProviders.single()
        assertEquals(null, provider.export("steve"))

        context.run("perm.user.grant", "steve", "helix.fly")

        assertTrue(provider.export("steve")!!.contains("helix.fly"))
        assertTrue(provider.delete("steve"))
        assertFalse(context.has("steve", "helix.fly"))
        assertFalse(provider.delete("steve"))
    }
}
