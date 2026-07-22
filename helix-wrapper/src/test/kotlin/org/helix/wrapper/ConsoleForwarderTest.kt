package org.helix.wrapper

import java.io.ByteArrayOutputStream
import kotlin.io.path.appendText
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleForwarderTest {
    @Test
    fun `forwards appended lines to the target`() {
        val file = createTempDirectory("console").resolve("console.in")
        file.writeText("")
        val target = ByteArrayOutputStream()
        val forwarder = ConsoleForwarder(file, target, startAtEnd = false)

        file.appendText("say hello\n")
        forwarder.pump()
        file.appendText("list\n")
        forwarder.pump()

        assertEquals("say hello\nlist\n", target.toString())
    }

    @Test
    fun `ignores content present before start when startAtEnd`() {
        val file = createTempDirectory("console").resolve("console.in")
        file.writeText("old-command\n")
        val target = ByteArrayOutputStream()
        val forwarder = ConsoleForwarder(file, target, startAtEnd = true)

        forwarder.pump()
        assertEquals("", target.toString())

        file.appendText("new-command\n")
        forwarder.pump()
        assertEquals("new-command\n", target.toString())
    }
}
