package org.helix.addons.cosmetics.paper

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddonCosmeticsTest {
    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    @Test
    fun `messages list worn cosmetics and removed players`() {
        assertEquals(
            """{"players":{"$a":{"wings":"wings_angel","headwear":null}},"remove":["$b"]}""",
            AddonCosmetics.message(mapOf(a to ("wings_angel" to null)), setOf(b)),
        )
        assertNull(AddonCosmetics.message(emptyMap(), emptySet()))
    }
}
