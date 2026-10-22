package org.helix.node.release

import org.helix.api.action.ActionResult
import org.helix.api.release.ReleaseReport
import org.helix.api.release.ReleaseReset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseResetServiceTest {
    private val root: Path = createTempDirectory("release")

    private inner class FakeHost : ReleaseHost {
        val paused = mutableMapOf("CityBuild" to false, "MineClash-Game" to true)
        val active = mutableMapOf("CityBuild" to 2, "MineClash-Game" to 1)
        val calls = mutableListOf<Pair<String, List<String>>>()
        val backups = mutableListOf<String>()
        val events = mutableListOf<String>()
        override var maintenance: Boolean = false

        override fun taskExists(task: String) = task in paused
        override fun isPaused(task: String) = paused[task] ?: false
        override fun setPaused(task: String, paused: Boolean) { this.paused[task] = paused }
        override fun stopServices(task: String): List<String> = List(active[task] ?: 0) { "$task-${it + 1}" }.also { active[task] = 0 }
        override fun killServices(task: String) { active[task] = 0 }
        override fun activeCount(task: String) = active[task] ?: 0
        override fun staticWorkspaces(task: String): List<Pair<String, Path>> =
            if (task == "CityBuild") listOf("CityBuild-1" to root.resolve("CityBuild-1")) else emptyList()
        override fun backupService(serviceId: String) = "backup.zip".also { backups += serviceId }
        override fun backupData(): String = throw IllegalStateException("postgres mode")
        override fun participants() = listOf("eco.release.reset", "perm.release.reset")
        override fun invoke(action: String, arguments: List<String>): ActionResult {
            calls += action to arguments
            val mode = arguments.first()
            return ActionResult.ok(ReleaseReport(action.substringBefore('.'), mode, deleted = mapOf("rows" to 3)).toJson())
        }
        override fun event(level: String, message: String) { events += "$level $message" }
    }

    private fun workspace(): Path {
        val ws = root.resolve("CityBuild-1").createDirectories()
        listOf("spawn", "plots", "farm").forEach { ws.resolve(it).createDirectories().resolve("level.dat").writeText("x") }
        ws.resolve("plugins/PlotSquared").createDirectories().resolve("storage.db").writeText("db")
        ws.resolve("plugins/CityBuild").createDirectories().resolve("locations.yml").writeText("spots")
        return ws
    }

    private val config = ReleaseConfig(
        tasks = listOf(
            ReleaseTask("CityBuild", listOf("plots", "farm", "plugins/PlotSquared/*.db", "../escape")),
            ReleaseTask("MineClash-Game", listOf("village_*")),
            ReleaseTask("Unknown", listOf("x")),
        ),
    )

    @Test
    fun `plan lists stops, wipes and participant counts without changing anything`() {
        val ws = workspace()
        val host = FakeHost()
        val lines = ReleaseResetService(host, { config }).plan()
        assertTrue(lines.any { it.contains("CityBuild-1: wipe 3 path(s)") }, lines.joinToString("\n"))
        assertTrue(lines.any { it.contains("Unknown: not configured") })
        assertTrue(lines.any { it.startsWith("eco: would delete rows=3") })
        assertTrue(host.calls.all { it.second == listOf(ReleaseReset.PLAN) })
        assertTrue(ws.resolve("plots").exists() && host.active["CityBuild"] == 2 && !host.maintenance)
    }

    @Test
    fun `a wrong phrase is rejected`() {
        val result = ReleaseResetService(FakeHost(), { config }, launch = { it.run() }).run("nope", removeTesters = false, keepMaintenance = false)
        assertFalse(result.success)
        assertEquals("wrong_phrase", result.lines[0])
    }

    @Test
    fun `run stops, backs up, resets addons, wipes worlds and reopens the network`() {
        val ws = workspace()
        val host = FakeHost()
        val service = ReleaseResetService(host, { config }, sleep = {}, launch = { it.run() })
        assertTrue(service.run(ReleaseConfig.DEFAULT_PHRASE, removeTesters = true, keepMaintenance = false).success)
        val status = service.status()
        assertEquals(ReleaseStatus.DONE, status.state, status.steps.joinToString("\n"))
        assertEquals(listOf("CityBuild-1"), host.backups)
        assertTrue(host.calls.all { it.second == listOf(ReleaseReset.RUN, ReleaseReset.FLAG_REMOVE_TESTERS) })
        assertEquals(2, status.reports.size)
        assertFalse(ws.resolve("plots").exists())
        assertFalse(ws.resolve("farm").exists())
        assertFalse(ws.resolve("plugins/PlotSquared/storage.db").exists())
        assertTrue(ws.resolve("spawn/level.dat").exists(), "the built spawn world stays")
        assertTrue(ws.resolve("plugins/CityBuild/locations.yml").exists())
        assertTrue(Files.exists(root), "nothing outside the workspace is touched")
        assertEquals(false, host.paused["CityBuild"], "resumed")
        assertEquals(true, host.paused["MineClash-Game"], "a task paused before stays paused")
        assertFalse(host.maintenance)
        assertTrue(status.steps.any { it.startsWith("data backup skipped") })
    }

    @Test
    fun `keep maintenance leaves the network closed and a running reset blocks a second one`() {
        workspace()
        val host = FakeHost()
        var pending: Runnable? = null
        val service = ReleaseResetService(host, { config }, sleep = {}, launch = { pending = it })
        assertTrue(service.run(ReleaseConfig.DEFAULT_PHRASE, removeTesters = false, keepMaintenance = true).success)
        assertEquals("running", service.run(ReleaseConfig.DEFAULT_PHRASE, removeTesters = false, keepMaintenance = true).lines[0])
        pending!!.run()
        assertTrue(host.maintenance)
        assertEquals(ReleaseStatus.DONE, service.status().state)
    }

    @Test
    fun `wipe targets never leave the workspace`() {
        val ws = workspace()
        val targets = WipeTargets.resolve(ws, listOf("..", "../CityBuild-1", "/etc", "plugins/../..", "", "plots"))
        assertEquals(listOf(ws.resolve("plots")), targets)
    }

    @Test
    fun `services that do not stop in time are killed`() {
        workspace()
        val host = object : ReleaseHost by FakeHost() {
            var count = 1
            var killed = false
            override fun stopServices(task: String) = listOf("$task-1")
            override fun activeCount(task: String) = if (killed) 0 else count
            override fun killServices(task: String) { killed = true }
        }
        var now = 0L
        val service = ReleaseResetService(host, { config.copy(tasks = config.tasks.take(1)) }, clock = { now }, sleep = { now += it }, stopTimeoutMs = 3000, launch = { it.run() })
        service.run(ReleaseConfig.DEFAULT_PHRASE, removeTesters = false, keepMaintenance = false)
        assertTrue(host.killed)
        assertTrue(service.status().steps.any { it.contains("killed after 3s") })
    }
}
