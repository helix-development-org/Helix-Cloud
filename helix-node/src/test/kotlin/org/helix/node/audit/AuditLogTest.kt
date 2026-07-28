package org.helix.node.audit

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditLogTest {
    private val file = createTempDirectory("audit").resolve("audit.jsonl")

    @Test
    fun `records newest first and filters by category`() {
        var now = 0L
        val log = AuditLog(FileAuditSink(file), clock = { now })
        now = 1; log.record("http", "helix", "GET /api/v1/tasks → 200")
        now = 2; log.record("action", "rest", "service.start Lobby")
        now = 3; log.record("http", "anonymous", "GET /api/v1/tasks → 401", "denied")

        assertEquals(listOf(3L, 2L, 1L), log.recent(10).map { it.epochMs })
        assertEquals(listOf("http", "http"), log.recent(10, "http").map { it.category })
        assertEquals("denied", log.recent(1, "http").first().outcome)
    }

    @Test
    fun `filters by actor and free-text search`() {
        var now = 0L
        val log = AuditLog(FileAuditSink(file), clock = { now })
        now = 1; log.record("action", "steve", "ban.set griefer 7d spamming")
        now = 2; log.record("action", "alex", "service.stop Lobby-1")
        now = 3; log.record("action", "steve", "kick griefer flooding chat")

        assertEquals(2, log.recent(10, actor = "steve").size)
        assertEquals(listOf("kick griefer flooding chat", "ban.set griefer 7d spamming"), log.recent(10, actor = "STEVE").map { it.summary })
        assertEquals(1, log.recent(10, search = "Lobby-1").size)
        assertEquals(1, log.recent(10, actor = "steve", search = "ban.set").size)
    }

    @Test
    fun `persists to file and reloads across instances`() {
        val log = AuditLog(file)
        log.record("node", "system", "Node started")
        log.flush()

        val reloaded = AuditLog(file)

        assertTrue(reloaded.recent(10).any { it.summary == "Node started" })
        assertTrue(file.toFile().readText().contains("Node started"))
    }

    @Test
    fun `record does not block the caller on a slow sink`() {
        val release = java.util.concurrent.CountDownLatch(1)
        val slowSink = object : AuditSink {
            override fun append(entry: org.helix.api.audit.AuditEntry) {
                release.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }

            override fun loadRecent(limit: Int): List<org.helix.api.audit.AuditEntry> = emptyList()

            override fun prune(olderThanEpochMs: Long) {}
        }
        val log = AuditLog(slowSink)

        val elapsedMs = kotlin.system.measureTimeMillis {
            log.record("node", "system", "should not block")
        }

        assertTrue(elapsedMs < 500, "record() blocked for ${elapsedMs}ms on a stuck sink")
        assertEquals(1, log.recent(10).size)
        release.countDown()
    }

    @Test
    fun `overflowing the write queue drops the oldest pending entry instead of blocking`() {
        val started = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val appended = java.util.concurrent.CopyOnWriteArrayList<String>()
        val blockingSink = object : AuditSink {
            private var first = true

            @Synchronized
            override fun append(entry: org.helix.api.audit.AuditEntry) {
                if (first) {
                    first = false
                    started.countDown()
                    gate.await(2, java.util.concurrent.TimeUnit.SECONDS)
                }
                appended += entry.summary
            }

            override fun loadRecent(limit: Int): List<org.helix.api.audit.AuditEntry> = emptyList()

            override fun prune(olderThanEpochMs: Long) {}
        }
        // A tiny queue so a couple of extra records overflow it while the writer
        // thread is stuck on the first (slow) append.
        val log = AuditLog(blockingSink, queueCapacity = 2)

        log.record("node", "system", "entry-1") // picked up by the writer immediately, blocks it
        assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS), "writer never picked up entry-1")
        log.record("node", "system", "entry-2")
        log.record("node", "system", "entry-3")
        log.record("node", "system", "entry-4") // queue capacity 2 -> drops entry-2
        gate.countDown()
        log.flush()

        assertFalse(appended.contains("entry-2"))
        assertTrue(appended.contains("entry-1"))
        assertTrue(appended.contains("entry-3"))
        assertTrue(appended.contains("entry-4"))
    }

    @Test
    fun `pruneExpired drops entries older than the retention window, in memory and on disk`() {
        var now = 0L
        val log = AuditLog(FileAuditSink(file), retentionDays = 1, clock = { now })
        now = 0; log.record("node", "system", "old entry")
        now = 2 * DAY_MS; log.record("node", "system", "recent entry")

        log.pruneExpired()

        assertEquals(listOf("recent entry"), log.recent(10).map { it.summary })
        assertTrue(!file.toFile().readText().contains("old entry"))
    }

    @Test
    fun `pruneExpired is a no-op when retention is disabled`() {
        var now = 0L
        val log = AuditLog(FileAuditSink(file), retentionDays = 0, clock = { now })
        log.record("node", "system", "kept forever")
        now = 365 * DAY_MS

        log.pruneExpired()

        assertEquals(listOf("kept forever"), log.recent(10).map { it.summary })
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
