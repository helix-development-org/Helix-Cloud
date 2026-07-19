package org.helix.wrapper

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WrapperTest {
    @Test
    fun `config loads from properties file`() {
        val file = createTempDirectory("wrapper").resolve("wrapper.properties")
        file.writeText(
            """
            serviceId=Lobby-1
            serverJar=server.jar
            memoryMb=2048
            jvmArgs=-XX:+UseG1GC -Dhelix=1
            serverArgs=--nogui
            """.trimIndent(),
        )

        val config = WrapperConfig.load(file)

        assertEquals("Lobby-1", config.serviceId)
        assertEquals(
            listOf("java", "-Xms2048M", "-Xmx2048M", "-XX:+UseG1GC", "-Dhelix=1", "-jar", "server.jar", "--nogui"),
            config.command(),
        )
    }

    @Test
    fun `config requires mandatory keys`() {
        val file = createTempDirectory("wrapper").resolve("wrapper.properties")
        file.writeText("serviceId=Lobby-1")

        assertFailsWith<IllegalArgumentException> { WrapperConfig.load(file) }
    }

    @Test
    fun `runner returns exit code of the process`() {
        val runner = ServerProcessRunner()

        assertEquals(0, runner.run(listOf("true")))
        assertEquals(1, runner.run(listOf("false")))
    }
}
