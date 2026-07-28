package org.helix.bridge.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PermissionSetDiffTest {
    @Test
    fun `first sync grants every node`() {
        val diff = PermissionSetDiff.diff(emptySet(), setOf("helix.fly", "helix.vanish"))

        assertEquals(setOf("helix.fly", "helix.vanish"), diff.toGrant)
        assertTrue(diff.toRevoke.isEmpty())
    }

    @Test
    fun `revokes nodes no longer granted`() {
        val diff = PermissionSetDiff.diff(setOf("helix.fly", "helix.vanish"), setOf("helix.fly"))

        assertTrue(diff.toGrant.isEmpty())
        assertEquals(setOf("helix.vanish"), diff.toRevoke)
    }

    @Test
    fun `identical sets produce no changes`() {
        val diff = PermissionSetDiff.diff(setOf("helix.fly"), setOf("helix.fly"))

        assertTrue(diff.isEmpty())
    }

    @Test
    fun `mixed grant and revoke`() {
        val diff = PermissionSetDiff.diff(setOf("helix.fly", "helix.old"), setOf("helix.fly", "helix.new"))

        assertEquals(setOf("helix.new"), diff.toGrant)
        assertEquals(setOf("helix.old"), diff.toRevoke)
    }
}
