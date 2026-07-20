package org.helix.node.audit

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditLogTest {
    private val file = createTempDirectory("audit").resolve("audit.jsonl")

    @Test
    fun `records newest first and filters by category`() {
        var now = 0L
        val log = AuditLog(file, clock = { now })
        now = 1; log.record("http", "helix", "GET /api/v1/tasks → 200")
        now = 2; log.record("action", "rest", "service.start Lobby")
        now = 3; log.record("http", "anonymous", "GET /api/v1/tasks → 401", "denied")

        assertEquals(listOf(3L, 2L, 1L), log.recent(10).map { it.epochMs })
        assertEquals(listOf("http", "http"), log.recent(10, "http").map { it.category })
        assertEquals("denied", log.recent(1, "http").first().outcome)
    }

    @Test
    fun `persists to file and reloads across instances`() {
        AuditLog(file).record("node", "system", "Node started")

        val reloaded = AuditLog(file)

        assertTrue(reloaded.recent(10).any { it.summary == "Node started" })
        assertTrue(file.toFile().readText().contains("Node started"))
    }
}
