package de.tytoss.iguard.trace

import de.tytoss.iguard.PLAYER
import de.tytoss.iguard.api.ExemptionGrant
import de.tytoss.iguard.api.IGuardExemptionScope
import de.tytoss.iguard.api.IGuardFrameReport
import de.tytoss.iguard.config.IGuardConfig
import de.tytoss.iguard.config.TraceConfig
import de.tytoss.iguard.environment
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.Vec3
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceHubTest {
    private val directory = createTempDirectory("iguard-traces")
    private var now = 1_760_000_000_000L

    private fun hub(config: TraceConfig = TraceConfig(1L shl 20, 600, 1_000)) = TraceHub(
        directory, config, AtomicReference(IGuardConfig.defaults().dynamic), "lobby-1",
        { "Tester" }, { "V_1_21_11" }, Logger.getLogger("test"), { now },
    )

    private fun trace(at: Long, environmentAt: Long) = FrameTrace(
        MovementFrame(PLAYER, at, at, "V_1_21_11", true, true, Vec3(0.5, 64.0, 0.5), 0f, 0f, true),
        "Tester", environment(environmentAt, Vec3(0.5, 64.0, 0.5)), 1, false, emptyList(), emptyList(), emptyMap(), Vec3(0.0, 0.0, 0.0),
        true, emptyMap(), emptyMap(), emptyMap(), emptyList(),
    )

    @Test
    fun `a recording writes header, environment once per snapshot, frames, markers, exemptions and end`() {
        val hub = hub()
        assertFalse(hub.wants(PLAYER))
        val session = hub.start(PLAYER, "ice run/1")
        assertTrue(hub.wants(PLAYER))
        assertEquals("ice_run_1", session.label)
        assertTrue(session.file.fileName.toString().startsWith("ice_run_1-"))

        hub.observe(trace(now, now - 10))
        hub.observe(trace(now + 50, now - 10))
        hub.observe(trace(now + 100, now + 90))
        assertTrue(hub.marker(PLAYER, "jump now"))
        hub.exemptionGranted(ExemptionGrant(PLAYER, now + 120, 1_500, "teleport", IGuardExemptionScope.MOVEMENT))
        val file = hub.stop(PLAYER)
        assertEquals(session.file, file)
        assertNull(hub.active(PLAYER))
        hub.shutdown()

        val types = file!!.readLines().map { TraceCodec.parseLine(it)!!["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("header", "env", "frame", "frame", "env", "frame", "marker", "exempt", "end"), types)
        assertEquals(3, session.frames)
        assertFalse(session.active)
    }

    @Test
    fun `the size limit stops a recording by itself`() {
        val hub = hub(TraceConfig(4_000, 600, 1_000))
        val session = hub.start(PLAYER, "big")
        repeat(20) { hub.observe(trace(now + it * 50L, now + it * 50L - 5)) }
        hub.shutdown()

        assertFalse(session.active)
        val last = TraceCodec.parseLine(session.file.readLines().last())!!
        assertEquals("end", last["type"]!!.jsonPrimitive.content)
        assertEquals("max-bytes", last["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the duration limit stops a recording by itself`() {
        val hub = hub(TraceConfig(1L shl 20, 10, 1_000))
        val session = hub.start(PLAYER, "long")
        hub.observe(trace(now + 11_000, now + 10_990))
        hub.shutdown()

        assertFalse(session.active)
        assertEquals("max-seconds", TraceCodec.parseLine(session.file.readLines().last())!!["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `listeners receive every frame report and can unregister`() {
        val hub = hub()
        val reports = mutableListOf<IGuardFrameReport>()
        val handle = hub.addListener { reports += it }
        assertTrue(hub.wants(PLAYER))
        hub.observe(trace(now, now))
        handle.close()
        assertFalse(hub.wants(PLAYER))
        hub.observe(trace(now + 50, now + 50))
        hub.shutdown()

        assertEquals(1, reports.size)
        assertEquals("movement", reports.single().frameType)
    }

    @Test
    fun `quitting ends the recording`() {
        val hub = hub()
        val session = hub.start(PLAYER, "quit")
        hub.remove(PLAYER)
        hub.shutdown()

        assertFalse(session.active)
        assertTrue(Files.exists(session.file))
        assertEquals("quit", TraceCodec.parseLine(session.file.readLines().last())!!["reason"]!!.jsonPrimitive.content)
    }
}
