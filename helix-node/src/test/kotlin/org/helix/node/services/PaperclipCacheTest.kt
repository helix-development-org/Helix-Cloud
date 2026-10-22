package org.helix.node.services

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaperclipCacheTest {
    @TempDir
    lateinit var dir: Path

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    @Test
    fun `an unfinished Paperclip run is not cached`() {
        val cache = PaperclipCache(dir.resolve("cache"))
        val workspace = dir.resolve("ws")
        write(workspace.resolve("cache/mojang_1.21.11.jar"), "partial")
        assertEquals(0, cache.harvest(workspace, "1.21.11"))
        assertFalse(Files.exists(dir.resolve("cache/1.21.11/cache/mojang_1.21.11.jar")))
    }

    @Test
    fun `a completed workspace seeds the next fresh one`() {
        val cache = PaperclipCache(dir.resolve("cache"))
        val done = dir.resolve("done")
        write(done.resolve("cache/mojang_1.21.11.jar"), "mojang")
        write(done.resolve("libraries/com/example/lib/1.0/lib-1.0.jar"), "lib")
        write(done.resolve("versions/1.21.11/paper-1.21.11.jar"), "patched")
        assertEquals(2, cache.harvest(done, "1.21.11"))

        val fresh = dir.resolve("fresh")
        Files.createDirectories(fresh)
        assertEquals(2, cache.seed(fresh, "1.21.11"))
        assertEquals("mojang", Files.readString(fresh.resolve("cache/mojang_1.21.11.jar")))
        assertTrue(Files.exists(fresh.resolve("libraries/com/example/lib/1.0/lib-1.0.jar")))
        assertEquals(0, cache.seed(fresh, "1.21.11"), "already complete files are not copied again")
    }

    @Test
    fun `versions are cached separately`() {
        val cache = PaperclipCache(dir.resolve("cache"))
        val done = dir.resolve("done")
        write(done.resolve("cache/mojang_1.21.11.jar"), "mojang")
        write(done.resolve("versions/1.21.11/paper-1.21.11.jar"), "patched")
        cache.harvest(done, "1.21.11")
        val other = dir.resolve("other")
        Files.createDirectories(other)
        assertEquals(0, cache.seed(other, "1.21.10"))
    }
}
