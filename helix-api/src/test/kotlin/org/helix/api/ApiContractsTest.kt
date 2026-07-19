package org.helix.api

import kotlinx.serialization.json.Json
import org.helix.api.action.ActionResult
import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.proxy.RoutingBackend
import org.helix.api.proxy.RoutingSnapshot
import org.helix.api.service.ServiceInfo
import org.helix.api.service.ServiceState
import org.helix.api.task.TaskDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiContractsTest {
    @Test
    fun `task definition serializes round trip`() {
        val task = TaskDefinition(
            name = "Lobby",
            environment = Environment.PAPER,
            version = "1.21.11",
            executor = ExecutorType.DOCKER,
            minServiceCount = 2,
            maxServiceCount = 4,
        )

        val decoded = Json.decodeFromString<TaskDefinition>(Json.encodeToString(task))

        assertEquals(task, decoded)
    }

    @Test
    fun `task definition validates bounds`() {
        assertFailsWith<IllegalArgumentException> {
            TaskDefinition(name = "", environment = Environment.PAPER, version = "1.21.11")
        }
        assertFailsWith<IllegalArgumentException> {
            TaskDefinition(
                name = "Lobby",
                environment = Environment.PAPER,
                version = "1.21.11",
                minServiceCount = 3,
                maxServiceCount = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TaskDefinition(name = "Lob by", environment = Environment.PAPER, version = "1.21.11")
        }
    }

    @Test
    fun `service info serializes round trip`() {
        val info = ServiceInfo(
            id = "Lobby-1",
            taskName = "Lobby",
            environment = Environment.PAPER,
            executor = ExecutorType.PROCESS,
            state = ServiceState.RUNNING,
            port = 25565,
            static = false,
            onlinePlayers = 3,
            maxPlayers = 100,
            startedAtEpochMs = 1234L,
        )

        assertEquals(info, Json.decodeFromString<ServiceInfo>(Json.encodeToString(info)))
    }

    @Test
    fun `routing snapshot serializes round trip`() {
        val snapshot = RoutingSnapshot(
            backends = listOf(
                RoutingBackend("Lobby-1", "Lobby", "127.0.0.1", 25565, fallbackEligible = true),
            ),
            maintenance = true,
        )

        assertEquals(snapshot, Json.decodeFromString<RoutingSnapshot>(Json.encodeToString(snapshot)))
    }

    @Test
    fun `action result helpers set success flag`() {
        assertTrue(ActionResult.ok("done").success)
        assertFalse(ActionResult.error("boom").success)
        assertEquals(listOf("boom"), ActionResult.error("boom").lines)
    }

    @Test
    fun `environment knows proxy platforms`() {
        assertTrue(Environment.VELOCITY.proxy)
        assertFalse(Environment.PAPER.proxy)
    }
}
