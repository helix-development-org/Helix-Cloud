package org.helix.addons.mineclash.war

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Round trips of the clan-war wire format against the fixtures shared with the Paper side. */
class WarProtocolTest {
    private fun fixture(name: String): String =
        javaClass.getResource("/war-fixtures/$name")?.readText() ?: error("fixture $name missing")

    private fun tree(raw: String): JsonElement = WarProtocol.json.parseToJsonElement(raw)

    @Test
    fun `status fixture parses and survives a round trip`() {
        val status = WarProtocol.status(fixture("status.json"))
        assertEquals("b-7f3c2a", status.bootId)
        assertEquals(2, status.capacity)
        assertEquals(PoolStatus(2, 2, true, 0.42), status.pool)
        assertEquals(listOf(WarProtocol.Phase.PREPARE, WarProtocol.Phase.RUNNING), status.wars.map { it.phase })
        assertEquals(1792700000000, status.wars[0].phaseEndsAt)
        assertNull(status.wars[1].phaseEndsAt)
        assertEquals(mapOf("A" to 5, "B" to 0), status.wars[1].present)
        assertEquals(listOf("w-20261022-0003"), status.rejected)
        val again = WarProtocol.status(WarProtocol.json.encodeToString(WarServerStatus.serializer(), status))
        assertEquals(status, again)
    }

    @Test
    fun `minimal status uses defaults`() {
        val status = WarProtocol.status(fixture("status-minimal.json"))
        assertEquals(WarServerStatus("b-0001"), status)
        assertEquals(1, status.capacity)
        assertTrue(status.wars.isEmpty())
    }

    @Test
    fun `desired state encodes to the fixture`() {
        val state = WarDesiredState(
            returnTo = "MineClash-Game-1",
            wars = listOf(
                WarSpec(
                    "w-20261022-0001", 120, 10, 15,
                    listOf(
                        WarTeamSpec(
                            "A", "kronritter", "Kronritter", "KRON",
                            listOf(
                                WarMember("6f1b2c3d-1111-4a2b-9c3d-000000000001", "Tytoss"),
                                WarMember("6f1b2c3d-1111-4a2b-9c3d-000000000002", "Alice"),
                            ),
                        ),
                        WarTeamSpec("B", "zulus", "Zulus", "ZULU"),
                    ),
                ),
            ),
            cancel = listOf(WarCancel("w-20261021-0009", WarProtocol.CancelReason.SERVER_LOST)),
        )
        assertEquals(tree(fixture("desired-state.json")), tree(WarProtocol.encode(state)))
        val empty = WarProtocol.json.decodeFromString(WarDesiredState.serializer(), fixture("desired-state-empty.json"))
        assertEquals(WarDesiredState("MineClash-Game-1"), empty)
    }

    @Test
    fun `arrival decisions encode to the fixtures`() {
        assertEquals(tree(fixture("arrive-ok.json")), tree(WarProtocol.encode(WarArrival(true, "w-20261022-0001", "A"))))
        val refused = WarArrival(false, reason = "<#ff5a4a>Dein Clan ist gerade in keinem Krieg.")
        assertEquals(tree(fixture("arrive-refused.json")), tree(WarProtocol.encode(refused)))
        assertFalse("warId" in tree(WarProtocol.encode(refused)).jsonObject, "nulls are not written")
    }

    @Test
    fun `result fixtures parse and survive a round trip`() {
        val result = WarProtocol.result(fixture("result.json"))
        assertEquals("A", result.winner)
        assertEquals(WarProtocol.EndReason.POINT_LIMIT, result.reason)
        assertEquals(mapOf("A" to 5000, "B" to 3120), result.points)
        assertEquals(listOf(true, false), result.participants.map { it.presentAtEnd })
        assertEquals(result, WarProtocol.result(WarProtocol.json.encodeToString(WarResult.serializer(), result)))
        val draw = WarProtocol.result(fixture("result-draw.json"))
        assertNull(draw.winner)
        assertEquals(WarProtocol.EndReason.BOTH_ABSENT, draw.reason)
    }
}
