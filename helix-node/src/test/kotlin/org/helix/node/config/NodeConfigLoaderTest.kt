package org.helix.node.config

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeConfigLoaderTest {
    @Test
    fun `missing file yields defaults`() {
        val config = NodeConfigLoader().load(createTempDirectory("helix"))

        assertEquals(NodeConfig(), config)
    }

    @Test
    fun `file values override defaults`() {
        val root = createTempDirectory("helix")
        Files.createDirectories(root.resolve("config"))
        root.resolve("config/node.toml").writeText(
            """
            [control]
            host = "0.0.0.0"
            port = 9090
            token = "secret"

            [docker]
            network = "helix-net"

            [proxy]
            forwardingSecret = "fwd-secret"
            legacyForwarding = true

            [eula]
            accept = true
            acceptedBy = "operator"
            """.trimIndent(),
        )

        val config = NodeConfigLoader().load(root)

        assertEquals("0.0.0.0", config.control.host)
        assertEquals(9090, config.control.port)
        assertEquals("secret", config.control.token)
        assertEquals("helix-net", config.docker.network)
        assertEquals(NodeConfig.DockerSettings().image, config.docker.image)
        assertEquals("fwd-secret", config.proxy.forwardingSecret)
        assertEquals(true, config.proxy.legacyForwarding)
        assertEquals(true, config.eula.accept)
        assertEquals("operator", config.eula.acceptedBy)
    }
}
