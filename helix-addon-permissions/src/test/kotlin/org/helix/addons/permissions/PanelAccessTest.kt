package org.helix.addons.permissions

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelAccessTest {
    private val storage = InMemoryAddonStorage()
    private val store = PermissionStore(InMemoryAddonStorage())
    private val access = PanelAccess(store, storage)

    @Test
    fun `presets go to team groups and testers once, never over own choices or wildcards`() {
        store.saveGroup(PermissionGroup("owner", 100, permissions = listOf("*")))
        store.saveGroup(PermissionGroup("support", 60))
        store.saveGroup(PermissionGroup("mod", 70, permissions = listOf("helix.panel.overview")))
        store.saveGroup(PermissionGroup("developer", 90, permissions = listOf("some.other.node")))
        store.saveGroup(PermissionGroup("tester", 10, permissions = listOf("feedback.report")))

        val changed = access.presetsOnce()

        assertEquals(setOf("support", "developer", "tester"), changed.toSet())
        assertEquals(listOf("*"), store.group("owner")!!.permissions)
        assertEquals(listOf("helix.panel.overview"), store.group("mod")!!.permissions, "a group with own dashboard nodes stays")
        assertTrue("feedback.staff" in store.group("support")!!.permissions)
        assertTrue("helix.panel.services.logs" in store.group("developer")!!.permissions)
        assertTrue("some.other.node" in store.group("developer")!!.permissions, "presets only add")
        assertEquals(
            setOf("feedback.report") + PanelAccess.TESTER,
            store.group("tester")!!.permissions.toSet(),
            "testers see nothing of the team dashboard",
        )
        assertTrue(access.presetsOnce().isEmpty(), "runs once per group")
    }

    @Test
    fun `presets never grant admin-equivalent nodes`() {
        PanelAccess.PRESETS.forEach { (group, nodes) ->
            assertTrue(nodes.none { it in PanelAccess.CRITICAL }, "preset $group contains a critical node")
        }
    }

    @Test
    fun `a handled group is never given its preset again, a negated node counts as configured`() {
        store.saveGroup(PermissionGroup("support", 60))
        store.saveGroup(PermissionGroup("mod", 70, permissions = listOf("-helix.panel.login")))
        access.presetsOnce()
        assertEquals(listOf("-helix.panel.login"), store.group("mod")!!.permissions, "negation = an admin decided")

        // The admin clears the support group completely: neither the startup run nor the action re-grants it.
        store.saveGroup(PermissionGroup("support", 60))
        assertTrue(access.presetsOnce().isEmpty())
        assertTrue(access.applyPresets().isEmpty(), "without names only never-handled groups")
        assertTrue(store.group("support")!!.permissions.isEmpty())
        // Named explicitly by an admin it gets the preset again.
        assertEquals(listOf("support"), access.applyPresets(listOf("support")))
    }

    @Test
    fun `installations of the first preset version keep their groups untouched`() {
        storage.write(PanelAccess.VERSION_KEY, "1")
        store.saveGroup(PermissionGroup("support", 60))
        assertTrue(access.presetsOnce().isEmpty())
        assertTrue(store.group("support")!!.permissions.isEmpty())
    }

    @Test
    fun `granting the team bug node brings the reporting node`() {
        store.saveGroup(PermissionGroup("mod", 70))
        assertNull(access.set("mod", "feedback.staff", true))
        assertTrue("feedback.report" in store.group("mod")!!.permissions)
        assertNull(access.set("mod", "feedback.staff", false))
        assertFalse("feedback.staff" in store.group("mod")!!.permissions)
    }

    @Test
    fun `no preset writes to consoles, admins may control services`() {
        store.saveGroup(PermissionGroup("support", 60))
        store.saveGroup(PermissionGroup("admin", 80))
        store.saveGroup(PermissionGroup("developer", 90))
        access.applyPresets()

        assertFalse("helix.panel.services.console" in store.group("support")!!.permissions)
        assertFalse("helix.panel.services.console" in store.group("admin")!!.permissions)
        assertTrue("helix.panel.services.control" in store.group("admin")!!.permissions)
        assertFalse("helix.panel.services.console" in store.group("developer")!!.permissions, "granted deliberately in the matrix")
    }

    @Test
    fun `the matrix changes only dashboard nodes and never wildcard groups`() {
        store.saveGroup(PermissionGroup("manager", 95, permissions = listOf("*")))
        store.saveGroup(PermissionGroup("mod", 70))

        assertNull(access.set("mod", "helix.panel.players.manage", true))
        assertNull(access.set("mod", "feedback.staff", true))
        assertEquals(listOf("helix.panel.players.manage", "feedback.staff", "feedback.report"), store.group("mod")!!.permissions)
        assertNull(access.set("mod", "helix.panel.players.manage", false))
        assertEquals(listOf("feedback.staff", "feedback.report"), store.group("mod")!!.permissions)

        assertNotNull(access.set("mod", "*", true))
        assertNotNull(access.set("mod", "helix.admin", true))
        assertNotNull(access.set("mod", "helix.panel.*", true))
        assertNotNull(access.set("mod", "mineclash.admin", true))
        assertNotNull(access.set("manager", "helix.panel.overview", false), "wildcard groups are locked")
        assertNotNull(access.set("ghost", "helix.panel.overview", true))
    }

    @Test
    fun `matrix shows own grants, inherited and wildcard groups`() {
        store.saveGroup(PermissionGroup("owner", 100, permissions = listOf("*")))
        store.saveGroup(PermissionGroup("support", 60, permissions = listOf("helix.panel.overview", "other.node")))
        store.saveGroup(PermissionGroup("mod", 70, permissions = listOf("helix.panel.services.*"), parents = listOf("support")))

        val matrix = access.matrix()

        assertEquals(listOf("owner", "mod", "support"), matrix.groups.map { it.name })
        val owner = matrix.groups.first { it.name == "owner" }
        assertTrue(owner.wildcard)
        val support = matrix.groups.first { it.name == "support" }
        assertEquals(listOf("helix.panel.overview"), support.granted)
        val mod = matrix.groups.first { it.name == "mod" }
        assertFalse(mod.wildcard)
        assertTrue("helix.panel.overview" in mod.inherited, "from the parent group")
        assertTrue("helix.panel.services.console" in mod.inherited, "from the partial wildcard")
        assertEquals(listOf("feedback.staff"), matrix.extras.map { it.node })
    }
}
