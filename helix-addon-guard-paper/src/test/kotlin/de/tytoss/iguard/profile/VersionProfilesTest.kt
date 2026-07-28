package de.tytoss.iguard.profile

import com.github.retrooper.packetevents.protocol.player.ClientVersion
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VersionProfilesTest {
    @Test
    fun `all packet events 1_21 protocol families are supported`() {
        val versions = listOf(
            ClientVersion.V_1_21, ClientVersion.V_1_21_2, ClientVersion.V_1_21_4,
            ClientVersion.V_1_21_5, ClientVersion.V_1_21_6, ClientVersion.V_1_21_7,
            ClientVersion.V_1_21_9, ClientVersion.V_1_21_11
        )

        versions.forEach { assertNotNull(VersionProfiles.forClient(it), "$it should be supported") }
    }

    @Test
    fun `older post-combat-update clients back to 1_9 are supported via ViaVersion`() {
        val versions = listOf(
            ClientVersion.V_1_9, ClientVersion.V_1_12_2, ClientVersion.V_1_16_4,
            ClientVersion.V_1_18_2, ClientVersion.V_1_20_5
        )

        versions.forEach { assertNotNull(VersionProfiles.forClient(it), "$it should be supported") }
    }

    @Test
    fun `versions outside the declared range are rejected`() {
        assertNull(VersionProfiles.forClient(ClientVersion.V_1_8))
        assertNull(VersionProfiles.forClient(ClientVersion.V_1_7_10))
        assertNull(VersionProfiles.forClient(ClientVersion.V_26_1))
    }
}
