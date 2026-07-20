package org.helix.node.storage

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileAddonStorageTest {
    private val storage = JsonFileAddonStorage(createTempDirectory("store"))

    @Test
    fun `write read delete and keys round trip`() {
        assertNull(storage.read("bans"))

        storage.write("bans", "[]")
        storage.write("config", "{}")

        assertEquals("[]", storage.read("bans"))
        assertEquals(setOf("bans", "config"), storage.keys().toSet())
        assertTrue(storage.delete("bans"))
        assertFalse(storage.delete("bans"))
        assertNull(storage.read("bans"))
    }

    @Test
    fun `json provider serves file storage per addon`() {
        val dir = createTempDirectory("addon")
        val a = JsonStorageProvider().forAddon("helix.bans", dir)
        a.write("bans", "data")

        // a second handle over the same directory sees persisted data
        assertEquals("data", JsonStorageProvider().forAddon("helix.bans", dir).read("bans"))
    }
}
