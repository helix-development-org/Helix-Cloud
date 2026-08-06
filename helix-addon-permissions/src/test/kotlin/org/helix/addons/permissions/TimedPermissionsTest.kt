package org.helix.addons.permissions

import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimedPermissionsTest {
    private var now = 1_000L
    private val store = PermissionStore(InMemoryAddonStorage(), clock = { now })

    @Test
    fun `timed personal permission expires`() {
        val user = store.user("steve")
        store.saveUser(user.copy(timedPermissions = listOf(TimedGrant("helix.fly", now + 60_000))))

        assertTrue(store.has("steve", "helix.fly"))
        now += 61_000
        assertFalse(store.has("steve", "helix.fly"))
        // expired grants are pruned from the profile
        assertTrue(store.user("steve").timedPermissions.isEmpty())
    }

    @Test
    fun `timed group membership expires`() {
        store.saveGroup(PermissionGroup(name = "vip", permissions = listOf("helix.vip.*")))
        store.saveUser(store.user("alex").copy(timedGroups = listOf(TimedGrant("vip", now + 60_000))))

        assertTrue(store.has("alex", "helix.vip.kit"))
        now += 61_000
        assertFalse(store.has("alex", "helix.vip.kit"))
    }

    @Test
    fun `user holding only timed grants is not purged on save`() {
        store.saveUser(store.user("steve").copy(timedPermissions = listOf(TimedGrant("helix.fly", now + 5_000))))

        assertEquals(1, store.document().users.size)
    }

    @Test
    fun `duration tokens parse and format`() {
        assertEquals(30_000L, GrantDuration.parseMillis("30s"))
        assertEquals(7L * 86_400_000, GrantDuration.parseMillis("7d"))
        assertNull(GrantDuration.parseMillis("perm"))
        assertTrue(GrantDuration.isDurationToken("12h"))
        assertFalse(GrantDuration.isDurationToken("helix.fly"))
        assertEquals("6d 23h", GrantDuration.format(6 * 86_400_000L + 23 * 3_600_000L + 5))
        assertEquals("expired", GrantDuration.format(-1))
    }
}
