package org.helix.node.actions

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeActionServiceTest {
    private val runs = AtomicInteger()
    private var now = 1_000L
    private val registry = ActionRegistry().apply {
        register(ActionDescriptor("demo.pay", "pays", "demo.pay", bridgeInvocable = true)) { ActionResult.ok("run ${runs.incrementAndGet()}") }
        register(ActionDescriptor("demo.city", "city only", "demo.city", bridgeInvocable = true, bridgeTasks = setOf("CityBuild"))) { ActionResult.ok("city") }
        register(ActionDescriptor("demo.admin", "not bridge", "demo.admin")) { ActionResult.ok("admin") }
    }
    private val tasks = mapOf("CityBuild-1" to "CityBuild", "Lobby-1" to "Lobby")
    private val service = BridgeActionService(registry, taskOf = { tasks[it] }, dedup = ActionDedup(ttlMs = 600_000, clock = { now }))

    @Test
    fun `same request id runs once and repeats the original result`() {
        val first = service.invoke(ActionInvocation("demo.pay", requestId = "r1"), "CityBuild-1")
        val second = service.invoke(ActionInvocation("demo.pay", requestId = "r1"), "CityBuild-1")
        assertEquals(1, runs.get())
        assertEquals(first.lines, second.lines)
    }

    @Test
    fun `different callers and missing ids are not deduplicated`() {
        service.invoke(ActionInvocation("demo.pay", requestId = "r1"), "CityBuild-1")
        service.invoke(ActionInvocation("demo.pay", requestId = "r1"), "Lobby-1")
        service.invoke(ActionInvocation("demo.pay"), "CityBuild-1")
        service.invoke(ActionInvocation("demo.pay"), "CityBuild-1")
        assertEquals(4, runs.get())
    }

    @Test
    fun `entries expire after the ttl`() {
        service.invoke(ActionInvocation("demo.pay", requestId = "r1"), "CityBuild-1")
        now += 600_000
        service.invoke(ActionInvocation("demo.pay", requestId = "r1"), "CityBuild-1")
        assertEquals(2, runs.get())
    }

    @Test
    fun `concurrent duplicates wait for the first execution`() {
        val gate = CountDownLatch(1)
        val slow = AtomicInteger()
        val reg = ActionRegistry().apply {
            register(ActionDescriptor("demo.slow", "slow", "demo.slow", bridgeInvocable = true)) {
                gate.await(5, TimeUnit.SECONDS)
                ActionResult.ok("slow ${slow.incrementAndGet()}")
            }
        }
        val svc = BridgeActionService(reg)
        val pool = Executors.newFixedThreadPool(4)
        val futures = (1..4).map { pool.submit<ActionResult> { svc.invoke(ActionInvocation("demo.slow", requestId = "x"), "CityBuild-1") } }
        Thread.sleep(200)
        gate.countDown()
        val results = futures.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, slow.get())
        assertTrue(results.all { it.success && it.lines == listOf("slow 1") })
    }

    @Test
    fun `task restricted actions only answer services of those tasks`() {
        assertTrue(service.invoke(ActionInvocation("demo.city"), "CityBuild-1").success)
        assertFalse(service.invoke(ActionInvocation("demo.city"), "Lobby-1").success)
        assertFalse(service.invoke(ActionInvocation("demo.city"), "Unknown-1").success)
        assertTrue(service.invoke(ActionInvocation("demo.city"), null).success, "admin token is never restricted")
    }

    @Test
    fun `non bridge actions are rejected`() {
        assertFalse(service.invoke(ActionInvocation("demo.admin"), "CityBuild-1").success)
    }

    @Test
    fun `eviction keeps the cache bounded`() {
        val dedup = ActionDedup(ttlMs = 600_000, maxEntries = 3, clock = { now })
        repeat(10) { i -> now += 1; dedup.once("k$i") { ActionResult.ok("$i") } }
        assertTrue(dedup.size <= 4)
    }
}
