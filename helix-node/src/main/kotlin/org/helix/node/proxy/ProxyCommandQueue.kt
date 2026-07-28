package org.helix.node.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import org.helix.api.proxy.ProxyCommand

/**
 * Per-proxy queues of pending commands, delivered to proxy bridges via the
 * long-poll's ack cursor (see [pending]/[acknowledge]) so a response lost in
 * transit never silently drops a command.
 */
class ProxyCommandQueue {
    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<Entry>>()
    private val sequence = AtomicLong(0)

    /**
     * Enqueues a command for a set of proxy services.
     *
     * @param proxyServiceIds targets, usually every active proxy.
     * @param command the command to deliver.
     */
    fun enqueue(proxyServiceIds: Collection<String>, command: ProxyCommand) {
        val seq = sequence.incrementAndGet()
        proxyServiceIds.forEach { id ->
            queues.computeIfAbsent(id) { ConcurrentLinkedQueue() }.add(Entry(seq, command))
        }
    }

    /**
     * Removes and returns all pending commands of one proxy immediately.
     *
     * Kept only for the deprecated `GET /internal/commands` endpoint; the
     * long-poll instead uses [pending]/[acknowledge] so a command is not
     * lost when the response carrying it never reaches the bridge.
     *
     * @param proxyServiceId the polling proxy service.
     * @return pending commands in enqueue order.
     */
    fun drain(proxyServiceId: String): List<ProxyCommand> {
        val queue = queues[proxyServiceId] ?: return emptyList()
        val drained = mutableListOf<ProxyCommand>()
        while (true) {
            drained += queue.poll()?.command ?: break
        }
        return drained
    }

    /**
     * The commands still queued for one proxy, without removing them — the
     * caller (the long-poll) only removes them once the proxy's NEXT poll
     * acknowledges having received them via [acknowledge].
     *
     * @param proxyServiceId the polling proxy service.
     * @return pending commands in enqueue order.
     */
    fun pending(proxyServiceId: String): List<ProxyCommand> =
        queues[proxyServiceId]?.map { it.command } ?: emptyList()

    /**
     * The sequence number a poll response must echo back as the ack cursor:
     * the highest sequence number currently queued, or [previous] when
     * nothing is queued (so the bridge's cursor stays put until there is
     * something new to acknowledge).
     *
     * @param proxyServiceId the polling proxy service.
     * @param previous the ack token last known for this proxy.
     * @return the token to send in this poll's response.
     */
    fun tokenFor(proxyServiceId: String, previous: Long): Long =
        queues[proxyServiceId]?.lastOrNull()?.seq ?: previous

    /**
     * Removes every command up to and including [upToSeq] — called when a
     * proxy's poll confirms it already received them on a previous response.
     *
     * @param proxyServiceId the acknowledging proxy service.
     * @param upToSeq the ack cursor the proxy last received.
     */
    fun acknowledge(proxyServiceId: String, upToSeq: Long) {
        val queue = queues[proxyServiceId] ?: return
        while (true) {
            val head = queue.peek() ?: break
            if (head.seq > upToSeq) break
            queue.poll()
        }
    }

    /**
     * One queued command with the sequence number it was enqueued under.
     *
     * @property seq monotonically increasing enqueue order, used as the ack cursor.
     * @property command the queued command.
     */
    private data class Entry(val seq: Long, val command: ProxyCommand)
}
