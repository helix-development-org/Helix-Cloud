package org.helix.node.scheduler

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.node.actions.ActionRegistry
import org.helix.node.storage.JsonStorageProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobSchedulerTest {
    private var now = 0L
    private val storage = JsonStorageProvider().forAddon("scheduler", createTempDirectory("helix"))

    @Test
    fun `tick does not block on a slow job action`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actions = ActionRegistry()
        actions.register(ActionDescriptor(name = "backup.run", description = "d", usage = "backup.run")) {
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
            ActionResult.ok("done")
        }
        val scheduler = JobScheduler(storage, actions, clock = { now })
        scheduler.save(ScheduledJob(id = "backup", action = "backup.run", everyMinutes = 1))

        val tickDurationMs = measureTimeMillis { scheduler.tick() }

        assertTrue(tickDurationMs < 500, "tick() blocked for ${tickDurationMs}ms on a slow job action")
        assertTrue(started.await(1, TimeUnit.SECONDS), "the job action never started")
        release.countDown()
    }

    @Test
    fun `runNow does not block the caller either`() {
        val release = CountDownLatch(1)
        val actions = ActionRegistry()
        actions.register(ActionDescriptor(name = "backup.run", description = "d", usage = "backup.run")) {
            release.await(2, TimeUnit.SECONDS)
            ActionResult.ok("done")
        }
        val scheduler = JobScheduler(storage, actions, clock = { now })
        scheduler.save(ScheduledJob(id = "backup", action = "backup.run", everyMinutes = 0))

        val elapsedMs = measureTimeMillis { scheduler.runNow("backup") }

        assertTrue(elapsedMs < 500, "runNow() blocked for ${elapsedMs}ms")
        release.countDown()
    }

    @Test
    fun `eventSink fires once the async action completes`() {
        val recorded = java.util.concurrent.CopyOnWriteArrayList<String>()
        val actions = ActionRegistry()
        actions.register(ActionDescriptor(name = "announce", description = "d", usage = "announce")) {
            ActionResult.ok()
        }
        val scheduler = JobScheduler(storage, actions, eventSink = { _, _, message -> recorded += message }, clock = { now })
        scheduler.save(ScheduledJob(id = "job-1", action = "announce", everyMinutes = 1))

        scheduler.tick()

        val deadline = System.currentTimeMillis() + 2_000
        while (recorded.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(recorded.any { it.contains("job-1") })
    }

    @Test
    fun `an unreadable job store is never overwritten by later saves`() {
        storage.write("jobs", "{ this is not a job list")
        val scheduler = JobScheduler(storage, ActionRegistry(), clock = { now })

        // fail-safe: run without jobs, but keep the document for manual repair
        assertTrue(scheduler.all().isEmpty())

        scheduler.save(ScheduledJob(id = "backup", action = "backup.run", everyMinutes = 1))

        assertEquals(listOf("backup"), scheduler.all().map { it.id })
        assertEquals("{ this is not a job list", storage.read("jobs"))
    }

    @Test
    fun `a readable job store still persists changes`() {
        val scheduler = JobScheduler(storage, ActionRegistry(), clock = { now })
        scheduler.save(ScheduledJob(id = "backup", action = "backup.run", everyMinutes = 1))

        // a fresh scheduler over the same storage sees the persisted job
        assertEquals(
            listOf("backup"),
            JobScheduler(storage, ActionRegistry(), clock = { now }).all().map { it.id },
        )
    }
}
