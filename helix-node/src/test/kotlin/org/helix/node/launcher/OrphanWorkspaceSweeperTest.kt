package org.helix.node.launcher

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrphanWorkspaceSweeperTest {
    @Test
    fun `removes directories with no matching live service and keeps the rest`() {
        val servicesTemp = createTempDirectory("helix-temp")
        val live = servicesTemp.resolve("Lobby-1").also { Files.createDirectories(it) }
        live.resolve("keep.txt").writeText("data")
        val orphan = servicesTemp.resolve("Lobby-2").also { Files.createDirectories(it) }
        orphan.resolve("stale.txt").writeText("junk")

        val removed = OrphanWorkspaceSweeper.sweep(servicesTemp, setOf("Lobby-1"))

        assertEquals(1, removed)
        assertTrue(live.exists())
        assertTrue(live.resolve("keep.txt").exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun `does nothing when the directory does not exist`() {
        val missing = createTempDirectory("helix-temp").resolve("does-not-exist")

        assertEquals(0, OrphanWorkspaceSweeper.sweep(missing, emptySet()))
    }

    @Test
    fun `does nothing when every workspace is live`() {
        val servicesTemp = createTempDirectory("helix-temp")
        val live = servicesTemp.resolve("Lobby-1").also { Files.createDirectories(it) }

        val removed = OrphanWorkspaceSweeper.sweep(servicesTemp, setOf("Lobby-1"))

        assertEquals(0, removed)
        assertTrue(live.exists())
    }
}
