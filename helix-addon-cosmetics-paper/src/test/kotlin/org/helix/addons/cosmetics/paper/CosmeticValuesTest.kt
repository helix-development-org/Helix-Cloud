package org.helix.addons.cosmetics.paper

import kotlin.test.Test
import kotlin.test.assertEquals

class CosmeticValuesTest {
    private val bridgeValues = mapOf(
        "cosmetic.wings.steve" to "1001",
        "cosmetic.headwear.steve" to "2001",
        "cosmetic.wings.alex" to "1002",
        "tablist.header" to "Welcome",
    )

    @Test
    fun `wings extracts only wings entries, parsed as ints`() {
        assertEquals(mapOf("steve" to 1001, "alex" to 1002), CosmeticValues.wings(bridgeValues))
    }

    @Test
    fun `headwear extracts only headwear entries, parsed as ints`() {
        assertEquals(mapOf("steve" to 2001), CosmeticValues.headwear(bridgeValues))
    }

    @Test
    fun `a non-numeric value is skipped instead of crashing`() {
        val corrupt = mapOf("cosmetic.wings.steve" to "not-a-number")
        assertEquals(emptyMap(), CosmeticValues.wings(corrupt))
    }
}
