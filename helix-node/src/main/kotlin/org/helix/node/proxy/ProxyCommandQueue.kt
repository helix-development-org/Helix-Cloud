package org.helix.node.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.helix.api.proxy.ProxyCommand

/**
 * Per-proxy queues of pending commands, drained by the proxy bridges on
 * their next sync.
 */
class ProxyCommandQueue {
    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<ProxyCommand>>()

    /**
     * Enqueues a command for a set of proxy services.
     *
     * @param proxyServiceIds targets, usually every active proxy.
     * @param command the command to deliver.
     */
    fun enqueue(proxyServiceIds: Collection<String>, command: ProxyCommand) {
        proxyServiceIds.forEach { id ->
            queues.computeIfAbsent(id) { ConcurrentLinkedQueue() }.add(command)
        }
    }

    /**
     * Removes and returns all pending commands of one proxy.
     *
     * @param proxyServiceId the polling proxy service.
     * @return pending commands in enqueue order.
     */
    fun drain(proxyServiceId: String): List<ProxyCommand> {
        val queue = queues[proxyServiceId] ?: return emptyList()
        val drained = mutableListOf<ProxyCommand>()
        while (true) {
            drained += queue.poll() ?: break
        }
        return drained
    }
}
