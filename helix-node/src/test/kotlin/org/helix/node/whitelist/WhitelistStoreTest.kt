package org.helix.node.whitelist

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhitelistStoreTest {
    @Test
    fun `disabled by default and empty`() {
        val store = WhitelistStore(createTempDirectory("whitelist").resolve("whitelist.json"))

        assertFalse(store.isEnabled())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `add and remove are case-insensitive`() {
        val store = WhitelistStore(createTempDirectory("whitelist").resolve("whitelist.json"))

        assertTrue(store.add("Steve"))
        assertFalse(store.add("STEVE"))
        assertTrue(store.contains("steve"))
        assertEquals(listOf("steve"), store.all())

        assertTrue(store.remove("sTeVe"))
        assertFalse(store.contains("steve"))
        assertFalse(store.remove("steve"))
    }

    @Test
    fun `state survives a reload from disk`() {
        val file = createTempDirectory("whitelist").resolve("whitelist.json")
        val store = WhitelistStore(file)
        store.setEnabled(true)
        store.add("alex")

        val reloaded = WhitelistStore(file)

        assertTrue(reloaded.isEnabled())
        assertEquals(listOf("alex"), reloaded.all())
    }
}
