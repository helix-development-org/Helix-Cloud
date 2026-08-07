package org.helix.node.wire

import kotlinx.coroutines.runBlocking
import org.helix.api.proxy.ProxyPoll
import org.helix.node.control.ControlDependencies
import org.helix.wire.WireCodec
import org.helix.wire.WireServer
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pushes proxy command and routing changes to connected proxies over the
 * wire, replacing the HTTP long-poll natively.
 *
 * A single background thread waits on the [org.helix.node.proxy.ProxyEventHub]
 * exactly like the HTTP `poll` route's suspend loop; whenever routing, the
 * command catalog or a proxy's command queue changes, it pushes the current
 * [ProxyPoll] to every connected proxy under the `poll` category. The proxy
 * applies the commands and acknowledges them through the `poll-ack` wire
 * endpoint, so the same at-least-once, ack-cursor delivery guarantees as the
 * long-poll hold.
 *
 * @property dependencies the shared control dependencies.
 * @property server the wire server used to push.
 */
class WirePush(
    private val dependencies: ControlDependencies,
    private val server: WireServer,
) {
    private val logger = LoggerFactory.getLogger(WirePush::class.java)
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "helix-wire-push").apply { isDaemon = true }
    }

    /**
     * Starts the push loop.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        worker.execute { loop() }
    }

    /**
     * Stops the push loop.
     */
    fun stop() {
        running.set(false)
        worker.shutdownNow()
    }

    private fun loop() {
        val hub = dependencies.proxyEvents
        while (running.get()) {
            runCatching { pushAll(hub.routingVersion.get(), hub.commandCatalogVersion.get()) }
                .onFailure { logger.warn("Wire push failed", it) }
            runCatching { runBlocking { hub.await(RECHECK_MS) } }
        }
    }

    private fun pushAll(routingVersion: Int, catalogVersion: Int) {
        dependencies.playerRegistry.online()
            .map { it.proxyServiceId }
            .filter { it.isNotBlank() }
            .distinct()
            .plus(proxyServicesWithCommands())
            .distinct()
            .filter { server.isConnected(it) }
            .forEach { proxyServiceId ->
                val pending = dependencies.commandQueue.pending(proxyServiceId)
                val token = dependencies.commandQueue.tokenFor(pending, 0)
                val poll = ProxyPoll(pending.map { it.command }, routingVersion, catalogVersion, token)
                server.push(proxyServiceId, "poll", WireCodec.encode(poll))
            }
    }

    private fun proxyServicesWithCommands(): List<String> =
        dependencies.manager.services()
            .filter { it.environment.proxy }
            .map { it.id }

    private companion object {
        const val RECHECK_MS = 1_000L
    }
}
