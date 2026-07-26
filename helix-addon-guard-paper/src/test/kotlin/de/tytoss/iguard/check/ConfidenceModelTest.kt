package de.tytoss.iguard.check

import de.tytoss.iguard.config.ConfidenceConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfidenceModelTest {
    private val config = ConfidenceConfig(
        signal = mapOf("client.brand_spoof.a" to 0.25),
        defaultSignal = 0.50,
        singleFamilyCap = 0.79,
        multiFamilyCap = 0.95,
        deterministic = 0.85
    )

    @Test
    fun `single non-deterministic family cannot cross shadow threshold`() {
        assertEquals(0.79, ConfidenceModel.provisionalConfidence(config, listOf(0.95), deterministic = false))
    }

    @Test
    fun `independent strong families can cross shadow threshold`() {
        assertTrue(ConfidenceModel.provisionalConfidence(config, listOf(0.62, 0.60), deterministic = false) >= 0.80)
    }

    @Test
    fun `deterministic evidence reaches conservative proof tier`() {
        assertEquals(0.85, ConfidenceModel.provisionalConfidence(config, emptyList(), deterministic = true))
    }

    @Test
    fun `brand spoof remains a low confidence client indicator`() {
        assertEquals(0.25, ConfidenceModel.signalConfidence(config, "client.brand_spoof.a"))
    }

    @Test
    fun `unknown check falls back to default signal`() {
        assertEquals(0.50, ConfidenceModel.signalConfidence(config, "movement.unknown.z"))
    }
}
