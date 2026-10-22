package org.helix.addons.admin.paper

import kotlin.test.Test
import kotlin.test.assertEquals

class PanelUrlTest {
    @Test
    fun `without configuration the join host and control port are used`() {
        assertEquals("http://mc.example.com:8080", PanelUrl.resolve(null, "mc.example.com", 8080))
        assertEquals("http://127.0.0.1:8080", PanelUrl.resolve(" ", null, 8080))
    }

    @Test
    fun `http without port gets the control port`() {
        assertEquals("http://127.0.0.1:8080", PanelUrl.resolve("http://127.0.0.1", "x", 8080))
        assertEquals("http://127.0.0.1:8080", PanelUrl.resolve("http://127.0.0.1/", "x", 8080))
        assertEquals("http://panel.example.com:9000/helix", PanelUrl.resolve("panel.example.com/helix", "x", 9000))
    }

    @Test
    fun `explicit ports and https stay untouched`() {
        assertEquals("http://127.0.0.1:3000", PanelUrl.resolve("http://127.0.0.1:3000", "x", 8080))
        assertEquals("https://panel.example.com", PanelUrl.resolve("https://panel.example.com", "x", 8080))
    }
}
