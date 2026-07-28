package org.helix.node.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.helix.api.storage.InMemoryAddonStorage

class IdentityRegistryTest {
    @Test
    fun `join records both directions and is case-insensitive`() {
        val identities = IdentityRegistry(InMemoryAddonStorage())

        identities.recordJoin("Steve", "uuid-1")

        assertEquals("uuid-1", identities.resolveUuid("STEVE"))
        assertEquals("steve", identities.lastKnownName("uuid-1"))
    }

    @Test
    fun `a join without a uuid is ignored`() {
        val identities = IdentityRegistry(InMemoryAddonStorage())

        identities.recordJoin("Steve", null)

        assertNull(identities.resolveUuid("steve"))
    }

    @Test
    fun `a rename updates the forward mapping and drops the stale old name`() {
        val identities = IdentityRegistry(InMemoryAddonStorage())
        identities.recordJoin("Steve", "uuid-1")

        identities.recordJoin("Steve2", "uuid-1")

        assertEquals("uuid-1", identities.resolveUuid("steve2"))
        assertEquals("steve2", identities.lastKnownName("uuid-1"))
        // the old name no longer resolves to the renamed player, so a name-recycled
        // successor is never confused with them
        assertNull(identities.resolveUuid("steve"))
    }

    @Test
    fun `a recycled name resolves to its new owner, never the previous one`() {
        val identities = IdentityRegistry(InMemoryAddonStorage())
        identities.recordJoin("Steve", "uuid-1")
        identities.recordJoin("Steve2", "uuid-1")

        identities.recordJoin("Steve", "uuid-2")

        assertEquals("uuid-2", identities.resolveUuid("steve"))
        assertEquals("steve", identities.lastKnownName("uuid-2"))
        assertEquals("steve2", identities.lastKnownName("uuid-1"))
    }

    @Test
    fun `mapping persists across instances`() {
        val storage = InMemoryAddonStorage()
        IdentityRegistry(storage).recordJoin("Steve", "uuid-1")

        val reloaded = IdentityRegistry(storage)

        assertEquals("uuid-1", reloaded.resolveUuid("steve"))
        assertEquals("steve", reloaded.lastKnownName("uuid-1"))
    }
}
