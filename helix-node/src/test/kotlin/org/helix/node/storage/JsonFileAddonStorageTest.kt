package org.helix.node.storage

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileAddonStorageTest {
    private val directory = createTempDirectory("store")
    private val storage = JsonFileAddonStorage(directory)

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

    @Test
    fun `write leaves no partial document and keeps one backup generation`() {
        storage.write("economy", "v1")
        storage.write("economy", "v2")

        assertEquals("v2", storage.read("economy"))
        assertFalse(Files.exists(directory.resolve("economy.json.tmp")))
        assertEquals("v1", Files.readString(directory.resolve("economy.json.bak")))
    }

    @Test
    fun `a document corrupted after a simulated crash falls back to its backup`() {
        storage.write("economy", "v1")
        storage.write("economy", "v2")

        // simulate a crash mid-write: the live file holds truncated, invalid
        // UTF-8 bytes while the rotated backup still holds the last good write
        directory.resolve("economy.json").writeBytes(byteArrayOf(0xC3.toByte()))

        assertEquals("v1", storage.read("economy"))
    }

    @Test
    fun `a corrupted document with no backup surfaces instead of silently resetting`() {
        directory.resolve("fresh.json").writeBytes(byteArrayOf(0xC3.toByte()))

        assertFailsWith<IllegalStateException> { storage.read("fresh") }
    }

    @Test
    fun `keys with path traversal segments are rejected`() {
        assertFailsWith<IllegalArgumentException> { storage.write("../escape", "x") }
        assertFailsWith<IllegalArgumentException> { storage.write("nested/key", "x") }
        assertFailsWith<IllegalArgumentException> { storage.write("nested\\key", "x") }
        assertFailsWith<IllegalArgumentException> { storage.write(".hidden", "x") }
        assertFailsWith<IllegalArgumentException> { storage.read("../escape") }
        assertFailsWith<IllegalArgumentException> { storage.delete("../escape") }
    }
}
