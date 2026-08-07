package org.helix.api

import kotlinx.serialization.json.Json
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.bridge.ResourceProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeartbeatCompatTest {
    @Test
    fun `old heartbeat json without resource fields still decodes`() {
        val report = Json.decodeFromString<HeartbeatReport>(
            """{"serviceId":"Lobby-1","onlinePlayers":3,"maxPlayers":100,"tps":19.9}""",
        )

        assertEquals(-1, report.memoryUsedMb)
        assertEquals(-1, report.memoryMaxMb)
        assertEquals(-1.0, report.cpuPercent)
    }

    @Test
    fun `resource probe reports plausible values`() {
        assertTrue(ResourceProbe.memoryUsedMb() > 0)
        assertTrue(ResourceProbe.memoryMaxMb() >= ResourceProbe.memoryUsedMb())
        // cpu may legitimately be -1.0 on exotic JVMs, otherwise 0..100
        val cpu = ResourceProbe.cpuPercent()
        assertTrue(cpu == -1.0 || cpu in 0.0..100.0)
    }
}
