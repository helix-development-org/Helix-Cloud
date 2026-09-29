package org.helix.addons.admin.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaffOpsTest {
    @Test
    fun `every op has a staff permission and unknown ops are rejected`() {
        assertTrue(StaffOps.ops.all { StaffOps.permissionFor(it)!!.startsWith("helix.staff.") })
        assertNull(StaffOps.permissionFor("op.everything"))
        assertEquals("helix.staff.teleport", StaffOps.permissionFor("player.bring"))
        assertEquals("helix.staff.inventory", StaffOps.permissionFor("player.invsee"))
    }

    @Test
    fun `no op uses the helix admin prefix`() {
        assertTrue(StaffOps.ops.none { StaffOps.permissionFor(it)!!.startsWith("helix.admin") })
    }
}
