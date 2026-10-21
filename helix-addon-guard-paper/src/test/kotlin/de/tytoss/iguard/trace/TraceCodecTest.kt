package de.tytoss.iguard.trace

import de.tytoss.iguard.PLAYER
import de.tytoss.iguard.TARGET
import de.tytoss.iguard.api.ExemptionGrant
import de.tytoss.iguard.api.IGuardCheckResult
import de.tytoss.iguard.api.IGuardExemptionScope
import de.tytoss.iguard.environment
import de.tytoss.iguard.model.AttackFrame
import de.tytoss.iguard.model.BlockAction
import de.tytoss.iguard.model.BlockActionFrame
import de.tytoss.iguard.model.ClientAbilitiesFrame
import de.tytoss.iguard.model.ClientAction
import de.tytoss.iguard.model.ClientActionFrame
import de.tytoss.iguard.model.ClientIdentityFrame
import de.tytoss.iguard.model.ClientTickFrame
import de.tytoss.iguard.model.InventoryClickFrame
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.PacketFrame
import de.tytoss.iguard.model.ResetFrame
import de.tytoss.iguard.model.ServerAbilitiesFrame
import de.tytoss.iguard.model.ServerTeleportFrame
import de.tytoss.iguard.model.ServerVelocityFrame
import de.tytoss.iguard.model.SwingFrame
import de.tytoss.iguard.model.TeleportConfirmFrame
import de.tytoss.iguard.model.TimelineFrame
import de.tytoss.iguard.model.TimelineKind
import de.tytoss.iguard.model.Vec3
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TraceCodecTest {
    private val version = "V_1_21_11"

    @Test
    fun `environment lines round-trip exactly`() {
        val original = environment(1_234, Vec3(10.123456789, 64.0, -3.5), setOf("climbable", "low-tps"), slipperiness = 0.98, ping = 87, tps = 18.4)
        val decoded = TraceCodec.decodeEnvironment(assertNotNull(TraceCodec.parseLine(TraceCodec.environmentLine(original))))

        assertEquals(original, decoded)
    }

    @Test
    fun `every frame kind round-trips through a frame line`() {
        val frames: List<PacketFrame> = listOf(
            MovementFrame(PLAYER, 1, 100, version, true, true, Vec3(1.0, 64.42, -2.25), 12.5f, -30f, false),
            AttackFrame(PLAYER, 2, 101, version, 42),
            SwingFrame(PLAYER, 3, 102, version),
            ClientActionFrame(PLAYER, 4, 103, version, ClientAction.START_SPRINTING),
            ClientAbilitiesFrame(PLAYER, 5, 104, version, true),
            ClientTickFrame(PLAYER, 6, 105, version),
            TimelineFrame(PLAYER, 7, 106, version, TimelineKind.CLIENT_PONG, 77),
            BlockActionFrame(PLAYER, 8, 107, version, BlockAction.PLACE, 3, 63, -9, "NORTH", 12, 0.25f, 1f, 0.5f),
            InventoryClickFrame(PLAYER, 9, 108, version, 0, 14, 36, 0, "PICKUP"),
            InventoryClickFrame(PLAYER, 10, 109, version, 0, null, -999, -1, "THROW"),
            ServerVelocityFrame(PLAYER, 11, 110, version, Vec3(0.4, 0.36, -0.1), "explosion"),
            ServerTeleportFrame(PLAYER, 12, 111, version, 5, Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 0.0), 90f, 0f, true, false, true, false, false),
            TeleportConfirmFrame(PLAYER, 13, 112, version, 5),
            ServerAbilitiesFrame(PLAYER, 14, 113, version, false, true, false, 0.05f),
            ResetFrame(PLAYER, 15, 114, version, "respawn"),
        )
        frames.forEach { frame ->
            val line = TraceCodec.parseLine(TraceCodec.frameLine(trace(frame)))!!
            assertEquals(frame, TraceCodec.decodeFrame(line, PLAYER), FrameTrace.frameType(frame))
        }

        val identity = ClientIdentityFrame(PLAYER, 16, 115, version, "minecraft:brand", byteArrayOf(7, 118, 97, 110))
        val decoded = TraceCodec.decodeFrame(TraceCodec.parseLine(TraceCodec.frameLine(trace(identity)))!!, PLAYER) as ClientIdentityFrame
        assertEquals("minecraft:brand", decoded.channel)
        assertContentEquals(identity.payload, decoded.payload)
    }

    @Test
    fun `frame lines carry environment, internals, verdicts and violation levels`() {
        val environment = environment(90, Vec3(0.5, 64.0, 0.5), setOf("liquid-surface"))
        val trace = FrameTrace(
            MovementFrame(PLAYER, 1, 100, version, true, false, Vec3(0.5, 64.0, 0.8), 0f, 0f, true),
            "Tester", environment, 17, false, emptyList(),
            listOf(
                IGuardCheckResult("movement.speed.a", true, true, 1.25, 3.5, 5.0, mapOf("horizontal" to 0.61, "sprinting" to true)),
                IGuardCheckResult("movement.fly.a", false, false, 0.0, 0.0, 4.0, emptyMap()),
            ),
            mapOf("movement.speed.a" to 3.5), Vec3(0.0, 0.0, 0.3), false,
            mapOf("airTicks" to 3, "predictedHorizontal" to 0.21), mapOf("speedLimit" to 0.33), mapOf("note" to "x"),
            listOf(TraceTarget(42, TARGET, "Target", listOf(environment))),
        )
        val line = TraceCodec.parseLine(TraceCodec.frameLine(trace))!!

        assertEquals("frame", line["type"]!!.jsonPrimitive.content)
        assertEquals("movement", line["kind"]!!.jsonPrimitive.content)
        assertEquals("liquid-surface", line["env"]!!.jsonObject["tags"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("0.33", line["eval"]!!.jsonObject["limits"]!!.jsonObject["speedLimit"]!!.jsonPrimitive.content)
        assertEquals("3", line["eval"]!!.jsonObject["airTicks"]!!.jsonPrimitive.content)
        val speed = line["results"]!!.jsonArray.first().jsonObject
        assertEquals("1.25", speed["weight"]!!.jsonPrimitive.content)
        assertEquals("0.61", speed["evidence"]!!.jsonObject["horizontal"]!!.jsonPrimitive.content)
        assertEquals("3.5", line["vl"]!!.jsonObject["movement.speed.a"]!!.jsonPrimitive.content)

        val target = TraceCodec.parseLine(TraceCodec.targetLine(trace.targets.single()))!!
        assertEquals(environment, TraceCodec.decodeEnvironment(target["frames"]!!.jsonArray.single().jsonObject))
    }

    @Test
    fun `non-finite numbers survive as strings`() {
        val frame = MovementFrame(PLAYER, 1, 100, version, true, true, Vec3(Double.NaN, 64.0, Double.POSITIVE_INFINITY), 0f, Float.NaN, false)
        val decoded = TraceCodec.decodeFrame(TraceCodec.parseLine(TraceCodec.frameLine(trace(frame)))!!, PLAYER) as MovementFrame

        assertEquals(true, decoded.position.x.isNaN())
        assertEquals(Double.POSITIVE_INFINITY, decoded.position.z)
        assertEquals(true, decoded.pitch.isNaN())
    }

    @Test
    fun `exemption lines round-trip`() {
        val grant = ExemptionGrant(PLAYER, 5_000, 1_500, "teleport", IGuardExemptionScope.MOVEMENT)
        assertEquals(grant, TraceCodec.decodeExemption(TraceCodec.parseLine(TraceCodec.exemptionLine(grant))!!, PLAYER))
    }

    private fun trace(frame: PacketFrame) = FrameTrace(
        frame, "Tester", null, 0, false, emptyList(), emptyList(), emptyMap(), null, null, emptyMap(), emptyMap(), emptyMap(), emptyList(),
    )
}
