package org.helix.bridge.paper

import kotlin.test.Test
import kotlin.test.assertEquals

class LabyHudTest {
    @Test
    fun `messages are valid JSON with escaped values`() {
        assertEquals("""{"values":{"balance":{"value":"1.234 \"C\""}}}""", LabyHud.values("balance" to "1.234 \"C\""))
        assertEquals("""{"remove":["balance","energy"]}""", LabyHud.remove("balance", "energy"))
    }
}
