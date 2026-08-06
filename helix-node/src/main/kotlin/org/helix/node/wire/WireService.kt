package org.helix.node.wire

import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.helix.node.config.NodeConfig
import org.helix.node.control.ControlDependencies
import org.helix.node.control.auth.ServiceTokenRegistry
import org.helix.wire.WireServer

/**
 * Owns the node's Helix-Wire endpoint: the [WireServer], its endpoint
 * dispatch and the command/routing push loop.
 *
 * Only constructed and started when `[wire] enabled` is set; otherwise the
 * node runs on plain HTTP exactly as before. Authentication reuses the
 * [ServiceTokenRegistry] — a handshake token must resolve to the very
 * service id it claims, the same check the HTTP bearer auth performs.
 *
 * @property wire the wire configuration section.
 * @property control the control settings, for reusing the TLS keystore.
 * @property dependencies the shared control dependencies.
 * @property serviceTokens the per-service token registry.
 */
class WireService(
    private val wire: NodeConfig.WireSettings,
    private val control: NodeConfig.ControlSettings,
    private val dependencies: ControlDependencies,
    private val serviceTokens: ServiceTokenRegistry,
) {
    private var server: WireServer? = null
    private var push: WirePush? = null

    /**
     * Starts the wire server and push loop.
     */
    fun start() {
        val wireServer = WireServer(
            port = wire.port,
            host = control.host,
            authenticate = { serviceId, token -> serviceTokens.serviceIdFor(token) == serviceId },
            sslContext = if (wire.tls) tlsContext() else null,
        )
        WireDispatch(dependencies).registerOn(wireServer)
        wireServer.start()
        val pushLoop = WirePush(dependencies, wireServer)
        pushLoop.start()
        server = wireServer
        push = pushLoop
    }

    /**
     * Stops the push loop and the wire server.
     */
    fun stop() {
        push?.stop()
        server?.stop()
        push = null
        server = null
    }

    private fun tlsContext(): SSLContext {
        require(control.tlsKeystore.isNotBlank()) {
            "[wire] tls=true requires the control API's PKCS12 keystore ([control] tlsKeystore)"
        }
        val password = control.tlsKeystorePassword.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(control.tlsKeystore).use { load(it, password) }
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
    }
}
