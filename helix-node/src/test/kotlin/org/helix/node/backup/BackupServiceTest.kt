package org.helix.node.backup

import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupServiceTest {
    private val root = createTempDirectory("backup")
    private val staticDir = Files.createDirectories(root.resolve("services/static"))
    private val backupsDir = root.resolve("backups")
    private var active = false
    private var now = 1_000L
    private val service = BackupService(
        backupsDir = backupsDir,
        staticServicesDir = staticDir,
        isActive = { active },
        retention = 3,
        clock = { now },
    )

    private val addonsDataDir = root.resolve("addons/data")
    private val tasksDir = root.resolve("tasks")
    private val dataService = BackupService(
        backupsDir = backupsDir,
        staticServicesDir = staticDir,
        retention = 3,
        clock = { now },
        dataSources = mapOf("addons" to addonsDataDir, "tasks" to tasksDir),
    )

    private fun workspace(id: String) = Files.createDirectories(staticDir.resolve(id))

    @Test
    fun `create list restore roundtrip`() {
        val ws = workspace("Lobby-1")
        Files.createDirectories(ws.resolve("world"))
        ws.resolve("world/level.dat").writeText("original")
        ws.resolve("server.properties").writeText("port=25565")

        val info = service.create("Lobby-1")
        assertEquals("Lobby-1", info.serviceId)
        assertEquals(listOf(info.fileName), service.list("Lobby-1").map { it.fileName })

        // mutate + restore brings the original content back
        ws.resolve("world/level.dat").writeText("corrupted")
        ws.resolve("junk.txt").writeText("should disappear")
        service.restore("Lobby-1", info.fileName)

        assertEquals("original", ws.resolve("world/level.dat").readText())
        assertEquals("port=25565", ws.resolve("server.properties").readText())
        assertFalse(Files.exists(ws.resolve("junk.txt")))
    }

    @Test
    fun `restore is blocked while the service runs`() {
        workspace("Lobby-2").resolve("a.txt").writeText("x")
        val info = service.create("Lobby-2")

        active = true
        assertFailsWith<IllegalArgumentException> { service.restore("Lobby-2", info.fileName) }
    }

    @Test
    fun `retention keeps only the newest archives`() {
        workspace("Lobby-3").resolve("a.txt").writeText("x")
        repeat(5) {
            now += 60_000
            service.create("Lobby-3")
        }

        assertEquals(3, service.list("Lobby-3").size)
    }

    @Test
    fun `rejects unknown workspaces and traversal attempts`() {
        assertFailsWith<IllegalArgumentException> { service.create("does-not-exist") }
        assertFailsWith<IllegalArgumentException> { service.create("../escape") }
        assertFailsWith<IllegalArgumentException> { service.restore("Lobby-1", "../../secret.zip") }
        assertFalse(service.delete("nope", "nothing.zip"))
    }

    @Test
    fun `workspaces reports running state`() {
        workspace("Lobby-4")
        active = true

        val states = service.workspaces()
        assertTrue(states.any { it.serviceId == "Lobby-4" && it.running })
    }

    @Test
    fun `a zip-slip archive fails before the workspace is touched`() {
        val ws = workspace("Lobby-5")
        ws.resolve("keep.txt").writeText("precious")
        val archiveDir = Files.createDirectories(backupsDir.resolve("Lobby-5"))
        ZipOutputStream(Files.newOutputStream(archiveDir.resolve("evil.zip"))).use { zip ->
            zip.putNextEntry(ZipEntry("../../escape.txt"))
            zip.write("boom".toByteArray())
            zip.closeEntry()
        }

        assertFailsWith<IllegalArgumentException> { service.restore("Lobby-5", "evil.zip") }

        assertEquals("precious", ws.resolve("keep.txt").readText())
        Files.walk(root).use { stream ->
            assertTrue(stream.noneMatch { it.name == "escape.txt" }, "the zip-slip entry escaped")
        }
    }

    @Test
    fun `a corrupt archive fails before the workspace is touched`() {
        val ws = workspace("Lobby-6")
        ws.resolve("keep.txt").writeText("precious")
        Files.createDirectories(backupsDir.resolve("Lobby-6")).resolve("broken.zip").writeText("this is not a zip")

        assertFailsWith<IOException> { service.restore("Lobby-6", "broken.zip") }

        assertEquals("precious", ws.resolve("keep.txt").readText())
    }

    @Test
    fun `restore aborts before the swap when the service was started during extraction`() {
        val ws = workspace("Lobby-7")
        ws.resolve("keep.txt").writeText("precious")
        var activityChecks = 0
        val racing = BackupService(
            backupsDir = backupsDir,
            staticServicesDir = staticDir,
            // inactive on restore's entry check, active again by the pre-swap re-check
            isActive = { activityChecks++ > 0 },
            retention = 3,
            clock = { now },
        )
        val info = racing.create("Lobby-7")
        ws.resolve("keep.txt").writeText("changed while running")

        assertFailsWith<IllegalArgumentException> { racing.restore("Lobby-7", info.fileName) }

        assertEquals(2, activityChecks)
        assertEquals("changed while running", ws.resolve("keep.txt").readText())
    }

    @Test
    fun `createData captures current addon-storage contents and restoreData round trips`() {
        Files.createDirectories(addonsDataDir.resolve("economy"))
        addonsDataDir.resolve("economy/balances.json").writeText("""{"Steve":100}""")
        Files.createDirectories(tasksDir)
        tasksDir.resolve("Lobby.toml").writeText("name = \"Lobby\"")

        val info = dataService.createData()
        assertEquals("_addon-data", info.serviceId)

        // mutate the live data, then restore brings the captured snapshot back
        addonsDataDir.resolve("economy/balances.json").writeText("""{"Steve":0}""")
        addonsDataDir.resolve("economy/junk.json").writeText("should disappear")
        dataService.restoreData(info.fileName)

        assertEquals("""{"Steve":100}""", addonsDataDir.resolve("economy/balances.json").readText())
        assertEquals("name = \"Lobby\"", tasksDir.resolve("Lobby.toml").readText())
        assertFalse(Files.exists(addonsDataDir.resolve("economy/junk.json")))
    }

    @Test
    fun `createData and restoreData fail without configured data sources`() {
        assertFailsWith<IllegalStateException> { service.createData() }
        assertFailsWith<IllegalStateException> { service.restoreData("whatever.zip") }
    }
}
