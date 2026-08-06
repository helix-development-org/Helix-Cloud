package org.helix.node.proxy

import org.helix.api.proxy.ProxyCommand
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

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
     * acknowledges having received them via [acknowledge]. Each command
     * carries its sequence number so the caller derives the ack token from
     * this exact snapshot (see [tokenFor]) instead of re-reading the queue,
     * which could have grown in the meantime.
     *
     * @param proxyServiceId the polling proxy service.
     * @return pending commands in enqueue order, with their sequence numbers.
     */
    fun pending(proxyServiceId: String): List<QueuedCommand> =
        queues[proxyServiceId]?.map { QueuedCommand(it.seq, it.command) } ?: emptyList()

    /**
     * The sequence number a poll response must echo back as the ack cursor:
     * the highest sequence number among the commands ACTUALLY being returned
     * in this response, or [previous] when the response carries none (so the
     * bridge's cursor stays put until there is something new to acknowledge).
     *
     * Deriving the token from the returned snapshot — never from the live
     * queue — is deliberate: a command enqueued between reading [pending]
     * and building the response is not in this response, so its sequence
     * number must not be acknowledged by the bridge's next poll, or it would
     * be dropped without ever having been delivered.
     *
     * @param delivered the exact [pending] snapshot going into this response.
     * @param previous the ack token last known for this proxy.
     * @return the token to send in this poll's response.
     */
    fun tokenFor(delivered: List<QueuedCommand>, previous: Long): Long =
        delivered.lastOrNull()?.seq ?: previous

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
     * Discards a proxy's queue entirely, including any still-pending commands.
     *
     * Call this when the proxy service ends for good (stop/removal), so the
     * per-proxy queue does not live on unbounded for ids that will never poll
     * again. Commands queued for a proxy that is gone are meaningless — a
     * replacement proxy registers under a new service id and receives only
     * commands enqueued after that.
     *
     * Note: not yet wired into the service lifecycle; callers own that wiring.
     *
     * @param proxyServiceId the proxy service whose queue to discard.
     */
    fun drop(proxyServiceId: String) {
        queues.remove(proxyServiceId)
    }

    /**
     * One queued command with the sequence number it was enqueued under.
     *
     * @property seq monotonically increasing enqueue order, used as the ack cursor.
     * @property command the queued command.
     */
    private data class Entry(val seq: Long, val command: ProxyCommand)
}

/**
 * One pending command together with the sequence number it was enqueued
 * under, as returned by [ProxyCommandQueue.pending] — the sequence number is
 * what a poll response's ack token is derived from ([ProxyCommandQueue.tokenFor]).
 *
 * @property seq monotonically increasing enqueue order, used as the ack cursor.
 * @property command the queued command.
 */
data class QueuedCommand(val seq: Long, val command: ProxyCommand)
