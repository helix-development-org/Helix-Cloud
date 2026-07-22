package org.helix.node.files

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileManagerServiceTest {
    private val root = createTempDirectory("files")
    private val staticDir = Files.createDirectories(root.resolve("services/static"))
    private val tempDir = Files.createDirectories(root.resolve("services/temp"))
    private val templatesDir = Files.createDirectories(root.resolve("templates"))
    private val service = FileManagerService(staticDir, tempDir, templatesDir, maxEditableBytes = 1024)

    init {
        Files.createDirectories(staticDir.resolve("Lobby-1/plugins"))
        staticDir.resolve("Lobby-1/server.properties").writeText("port=25565")
        Files.createDirectories(templatesDir.resolve("default"))
        // a secret OUTSIDE every root, to prove traversal cannot reach it
        root.resolve("secret.txt").writeText("top secret")
    }

    @Test
    fun `lists roots and directories`() {
        assertEquals(listOf("template:default", "static:Lobby-1"), service.roots())
        val entries = service.list("static:Lobby-1", "")
        // directories first
        assertEquals(listOf("plugins", "server.properties"), entries.map { it.name })
        assertTrue(entries.first().directory)
    }

    @Test
    fun `read write delete roundtrip`() {
        service.write("static:Lobby-1", "configs/motd.yml", "line: hi")
        assertEquals("line: hi", service.read("static:Lobby-1", "configs/motd.yml").content)

        assertTrue(service.delete("static:Lobby-1", "configs"))
        assertFalse(service.delete("static:Lobby-1", "configs"))
    }

    @Test
    fun `rejects traversal in paths roots and ids`() {
        assertFailsWith<IllegalArgumentException> { service.read("static:Lobby-1", "../../secret.txt") }
        assertFailsWith<IllegalArgumentException> { service.write("static:Lobby-1", "../evil.txt", "x") }
        assertFailsWith<IllegalArgumentException> { service.list("static:..", "") }
        assertFailsWith<IllegalArgumentException> { service.list("static:../templates", "") }
        assertFailsWith<IllegalArgumentException> { service.delete("static:Lobby-1", "") }
        assertFailsWith<IllegalArgumentException> { service.list("cache:x", "") }
    }

    @Test
    fun `enforces the size limit`() {
        staticDir.resolve("Lobby-1/big.bin").writeText("x".repeat(2048))
        assertFailsWith<IllegalArgumentException> { service.read("static:Lobby-1", "big.bin") }
        assertFailsWith<IllegalArgumentException> { service.write("static:Lobby-1", "a.txt", "y".repeat(2048)) }
    }
}
