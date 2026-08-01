package org.helix.node.services

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ForwardingSecretTest {
    private val file = createTempDirectory("fwd").resolve("forwarding.secret")

    @Test
    fun `a configured secret wins and nothing is generated`() {
        assertEquals("configured", ForwardingSecret.resolve("configured", file))
        assertTrue(java.nio.file.Files.notExists(file))
    }

    @Test
    fun `a blank config generates once and stays stable across restarts`() {
        val first = ForwardingSecret.resolve("", file)

        assertTrue(first.length >= 32)
        assertEquals(first, ForwardingSecret.resolve("", file))
        assertNotEquals(first, ForwardingSecret.resolve("", createTempDirectory("fwd2").resolve("s")))
    }

    @Test
    fun `an empty persisted file is regenerated instead of reused`() {
        java.nio.file.Files.createDirectories(file.parent)
        file.writeText("  ")

        assertTrue(ForwardingSecret.resolve("", file).isNotBlank())
    }
}
