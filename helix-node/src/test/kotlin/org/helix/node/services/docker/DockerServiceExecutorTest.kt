package org.helix.node.services.docker

import org.helix.api.environment.Environment
import org.helix.api.task.TaskDefinition
import org.helix.node.config.NodeConfig
import org.helix.node.services.ServiceStartSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerServiceExecutorTest {
    private class FakeRunner(
        private val responses: (List<String>) -> CommandResult = { CommandResult(0, "") },
    ) : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>): CommandResult {
            commands += command
            return responses(command)
        }
    }

    private fun spec(environment: Environment = Environment.PAPER): ServiceStartSpec {
        val task = TaskDefinition(
            name = "Lobby",
            environment = environment,
            version = "1.21.11",
            memoryMb = 1024,
        )
        return ServiceStartSpec(
            serviceId = "Lobby-1",
            task = task,
            workspace = createTempDirectory("ws"),
            port = 30000,
            environmentVariables = mapOf("HELIX_SERVICE_ID" to "Lobby-1"),
        )
    }

    @Test
    fun `start builds docker run with network mount and env`() {
        val runner = FakeRunner()
        val executor = DockerServiceExecutor(NodeConfig.DockerSettings(), runner)

        executor.start(spec())

        val run = runner.commands.first { it.take(2) == listOf("docker", "run") }
        assertTrue(run.containsAll(listOf("--name", "helix-lobby-1")))
        assertTrue(run.any { it.endsWith(":/helix:z") }, "workspace mount must carry the selinux :z label")
        assertTrue(run.containsAll(listOf("--network", "helix")))
        assertTrue(run.containsAll(listOf("-e", "HELIX_SERVICE_ID=Lobby-1")))
        assertTrue(run.containsAll(listOf("--memory", "1536m")))
        assertEquals(listOf("java", "-jar", "Wrapper.jar"), run.takeLast(3))
    }

    @Test
    fun `configured user is passed and blank user keeps the image default`() {
        val withUser = FakeRunner()
        DockerServiceExecutor(NodeConfig.DockerSettings(user = "998:998"), withUser).start(spec())
        val runWithUser = withUser.commands.first { it.take(2) == listOf("docker", "run") }
        assertTrue(runWithUser.containsAll(listOf("--user", "998:998")))

        val withoutUser = FakeRunner()
        DockerServiceExecutor(NodeConfig.DockerSettings(), withoutUser).start(spec())
        val runWithout = withoutUser.commands.first { it.take(2) == listOf("docker", "run") }
        assertFalse(runWithout.contains("--user"))
    }

    @Test
    fun `paper backend port is not published to the host`() {
        val runner = FakeRunner()
        val executor = DockerServiceExecutor(NodeConfig.DockerSettings(), runner)

        executor.start(spec(Environment.PAPER))

        val run = runner.commands.first { it.take(2) == listOf("docker", "run") }
        assertFalse(run.contains("-p"), "a Paper backend must only be reachable over the docker network")
    }

    @Test
    fun `velocity proxy port is published to the host`() {
        val runner = FakeRunner()
        val executor = DockerServiceExecutor(NodeConfig.DockerSettings(), runner)

        executor.start(spec(Environment.VELOCITY))

        val run = runner.commands.first { it.take(2) == listOf("docker", "run") }
        assertTrue(run.containsAll(listOf("-p", "30000:30000")))
    }

    @Test
    fun `network is created when missing`() {
        val runner = FakeRunner { command ->
            if (command.getOrNull(2) == "inspect" && command.getOrNull(1) == "network") {
                CommandResult(1, "not found")
            } else {
                CommandResult(0, "")
            }
        }
        DockerServiceExecutor(NodeConfig.DockerSettings(network = "helix-x"), runner).start(spec())

        assertTrue(runner.commands.contains(listOf("docker", "network", "create", "helix-x")))
    }

    @Test
    fun `failing docker run throws`() {
        val runner = FakeRunner { command ->
            if (command.getOrNull(1) == "run") CommandResult(125, "boom") else CommandResult(0, "")
        }

        assertFailsWith<IllegalStateException> {
            DockerServiceExecutor(NodeConfig.DockerSettings(), runner).start(spec())
        }
    }

    @Test
    fun `handle waits for exit captures final logs and removes container`() {
        val latch = CountDownLatch(1)
        var observed = -100
        val runner = FakeRunner { command ->
            when (command.getOrNull(1)) {
                "wait" -> CommandResult(0, "137\n")
                "logs" -> CommandResult(0, "crash reason\n")
                else -> CommandResult(0, "")
            }
        }
        val handle = DockerServiceHandle("helix-lobby-1", runner, java.nio.file.Path.of("/tmp/helix-test"))

        handle.onExit { code ->
            observed = code
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(137, observed)
        val logsIndex = runner.commands.indexOfFirst { it.getOrNull(1) == "logs" }
        val removeIndex = runner.commands.indexOfFirst { it.getOrNull(1) == "rm" }
        assertTrue(logsIndex in 0 until removeIndex, "logs must be captured before container removal")
        assertEquals(listOf("crash reason"), handle.logs(10))
    }

    @Test
    fun `handle reads logs and state`() {
        val runner = FakeRunner { command ->
            when (command.getOrNull(1)) {
                "logs" -> CommandResult(0, "line1\nline2\n")
                "inspect" -> CommandResult(0, "true\n")
                else -> CommandResult(0, "")
            }
        }
        val handle = DockerServiceHandle("helix-lobby-1", runner, java.nio.file.Path.of("/tmp/helix-test"))

        assertEquals(listOf("line1", "line2"), handle.logs(10))
        assertTrue(handle.alive)

        handle.stop()
        handle.kill()
        assertTrue(runner.commands.contains(listOf("docker", "stop", "-t", "30", "helix-lobby-1")))
        assertTrue(runner.commands.contains(listOf("docker", "kill", "helix-lobby-1")))
    }
}
