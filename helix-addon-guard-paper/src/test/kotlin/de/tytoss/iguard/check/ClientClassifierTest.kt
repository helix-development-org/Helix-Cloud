package de.tytoss.iguard.check

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientClassifierTest {
    @Test
    fun `vanilla declaration is not treated as proof`() {
        val result = ClientClassifier.classify("vanilla", emptySet())

        assertEquals("Vanilla-compatible", result.family)
        assertFalse(result.suspicious)
    }

    @Test
    fun `vanilla brand with fabric channel is reported as spoof suspicion`() {
        val result = ClientClassifier.classify("vanilla", setOf("fabric:registry/sync"))

        assertEquals("Fabric / modded", result.family)
        assertFalse(result.suspicious)
        assertTrue(result.brandSpoofed)
        assertEquals("inconsistent", result.confidence)
    }

    @Test
    fun `normal fabric brand is not reported as spoofed`() {
        val result = ClientClassifier.classify("fabric", setOf("fabric:registry/sync"))

        assertEquals("Fabric / modded", result.family)
        assertFalse(result.suspicious)
        assertFalse(result.brandSpoofed)
    }

    @Test
    fun `generic registered channel does not make vanilla suspicious`() {
        val result = ClientClassifier.classify("vanilla", setOf("example:test"))

        assertEquals("Vanilla-compatible", result.family)
        assertFalse(result.brandSpoofed)
    }

    @Test
    fun `explicit wurst channel is suspicious`() {
        val result = ClientClassifier.classify("vanilla", setOf("wurst:client"))

        assertEquals("Wurst", result.family)
        assertTrue(result.suspicious)
    }

    @Test
    fun `brand payload supports minecraft varint strings`() {
        val brand = "fabric"
        val payload = byteArrayOf(brand.length.toByte()) + brand.encodeToByteArray()

        assertEquals(brand, ClientClassifier.decodeBrand(payload))
    }

    @Test
    fun `registered channels are bounded and parsed`() {
        val payload = "fabric:registry/sync\u0000example:test".encodeToByteArray()

        assertEquals(setOf("fabric:registry/sync", "example:test"), ClientClassifier.decodeRegisteredChannels(payload))
    }

    @Test
    fun `floodgate uuids are detected as bedrock`() {
        // Floodgate uuids have zero high bits; Java/online uuids do not; the null uuid is excluded.
        assertTrue(ClientClassifier.isBedrock(UUID(0L, 1234L)))
        assertFalse(ClientClassifier.isBedrock(UUID(0L, 0L)))
        assertFalse(ClientClassifier.isBedrock(UUID(0x1234_5678_9abc_def0L, 0x1L)))
    }
}
