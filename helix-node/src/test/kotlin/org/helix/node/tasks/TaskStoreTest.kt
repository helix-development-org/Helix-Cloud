package org.helix.node.tasks

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.task.AutoScaleSettings
import org.helix.api.task.TaskDefinition

class TaskStoreTest {
    private val lobby = TaskDefinition(
        name = "Lobby",
        environment = Environment.PAPER,
        version = "1.21.11",
        executor = ExecutorType.DOCKER,
        staticServices = false,
        minServiceCount = 1,
        maxServiceCount = 3,
        memoryMb = 2048,
        maxPlayers = 64,
        startPort = 25565,
        jvmArgs = listOf("-XX:+UseG1GC"),
        templates = listOf("default", "lobby"),
        fallbackEligible = true,
        autoScale = AutoScaleSettings(enabled = true, playerRatioThreshold = 0.75, idleStopSeconds = 120),
    )

    @Test
    fun `save and reload round trips a task`() {
        val store = TaskStore(createTempDirectory("tasks"))

        store.save(lobby)
        store.reload()

        assertEquals(lobby, store.find("Lobby"))
        assertEquals(listOf(lobby), store.all())
    }

    @Test
    fun `toml codec round trips all fields`() {
        assertEquals(lobby, TaskTomlCodec.parse(TaskTomlCodec.render(lobby)))
    }

    @Test
    fun `parse applies defaults for optional keys`() {
        val task = TaskTomlCodec.parse(
            """
            name = "Proxy"
            environment = "VELOCITY"
            version = "3.4.0"
            """.trimIndent(),
        )

        assertEquals(ExecutorType.PROCESS, task.executor)
        assertEquals(1, task.minServiceCount)
        assertEquals(listOf("default"), task.templates)
        assertEquals(AutoScaleSettings(), task.autoScale.copy(enabled = false))
    }

    @Test
    fun `reload skips broken task files instead of failing the boot`() {
        val directory = createTempDirectory("tasks")
        val store = TaskStore(directory)
        store.save(lobby)
        directory.resolve("Wrong.toml").writeText(TaskTomlCodec.render(lobby))
        directory.resolve("Broken.toml").writeText("name = \"Broken\"\nenvironment = \"PAPER\"\nversion = \"\"")

        val loaded = store.reload()

        assertEquals(listOf("Lobby"), loaded.map { it.name })
    }

    @Test
    fun `delete removes task and file`() {
        val directory = createTempDirectory("tasks")
        val store = TaskStore(directory)
        store.save(lobby)

        assertTrue(store.delete("Lobby"))
        store.reload()

        assertNull(store.find("Lobby"))
    }
}
