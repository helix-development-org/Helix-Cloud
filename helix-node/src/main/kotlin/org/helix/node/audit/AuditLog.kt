package org.helix.node.audit

import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.helix.api.audit.AuditEntry
import org.slf4j.LoggerFactory

/**
 * Complete, durable audit trail.
 *
 * Every recorded entry is kept in an in-memory ring buffer for fast
 * dashboard/API reads and handed to a dedicated writer thread that appends it
 * to the configured [AuditSink] (a JSONL file or the shared PostgreSQL/MongoDB
 * database). [record] never blocks on the durable write: a slow or
 * unreachable sink would otherwise stall every action/join/lifecycle event
 * network-wide while holding the lock. On startup the tail of the sink is
 * loaded back so the trail survives restarts.
 *
 * @property sink durable backend the trail is written to and read from.
 * @property capacity in-memory ring buffer size.
 * @property retentionDays hard cap on how long entries are kept, enforced
 *  by [pruneExpired]; `0` disables pruning (unlimited retention).
 * @property clock epoch millis source, injectable for tests.
 * @property queueCapacity bound of the pending-write queue; once full, the
 *   oldest pending write is dropped to make room for the newest.
 */
class AuditLog(
    private val sink: AuditSink,
    private val capacity: Int = 5000,
    private val retentionDays: Int = 0,
    private val clock: () -> Long = System::currentTimeMillis,
    private val queueCapacity: Int = 2000,
) {
    private val logger = LoggerFactory.getLogger(AuditLog::class.java)
    private val entries = ArrayDeque<AuditEntry>()
    private val queue = ArrayBlockingQueue<WorkItem>(queueCapacity)
    private val droppedSinceLastWarning = AtomicLong(0)
    private val lastOverflowWarnEpochMs = AtomicLong(0)
    private val writer = Thread(::drainLoop, "helix-audit-writer").apply {
        isDaemon = true
        start()
    }

    /**
     * Convenience constructor persisting to a JSONL file.
     *
     * @param file the `audit.jsonl` path.
     */
    constructor(file: Path) : this(FileAuditSink(file))

    init {
        sink.loadRecent(capacity).forEach { entries.addLast(it) }
    }

    /**
     * Records an audit entry: it lands in the in-memory ring buffer
     * immediately and is queued for the durable sink without blocking.
     *
     * @param category coarse grouping.
     * @param actor who caused it.
     * @param summary human readable description.
     * @param outcome `ok`, `denied`, `error` or `info`.
     */
    @Synchronized
    fun record(category: String, actor: String, summary: String, outcome: String = "ok") {
        val entry = AuditEntry(clock(), category, actor, summary, outcome)
        entries.addLast(entry)
        while (entries.size > capacity) {
            entries.removeFirst()
        }
        enqueue(entry)
    }

    /**
     * Returns the newest entries first, optionally filtered by category,
     * actor and/or a free-text search across the summary (action name,
     * affected player, service id — whatever the invocation carried).
     *
     * @param limit maximum number of entries.
     * @param category category to filter by, or `null` for all.
     * @param actor actor substring to filter by (case-insensitive), or `null` for all.
     * @param search summary substring to filter by (case-insensitive), or `null` for all.
     * @return matching entries, newest first.
     */
    @Synchronized
    fun recent(limit: Int, category: String? = null, actor: String? = null, search: String? = null): List<AuditEntry> =
        entries.toList()
            .let { list -> if (category == null) list else list.filter { it.category == category } }
            .let { list -> if (actor.isNullOrBlank()) list else list.filter { it.actor.contains(actor, ignoreCase = true) } }
            .let { list -> if (search.isNullOrBlank()) list else list.filter { it.summary.contains(search, ignoreCase = true) } }
            .takeLast(limit)
            .asReversed()

    /**
     * Enforces the hard retention cap: entries older than [retentionDays]
     * are dropped from both the in-memory ring buffer and the durable sink.
     * A no-op when retention is disabled (`retentionDays <= 0`).
     */
    @Synchronized
    fun pruneExpired() {
        if (retentionDays <= 0) {
            return
        }
        val cutoff = clock() - retentionDays * MILLIS_PER_DAY
        entries.removeAll { it.epochMs < cutoff }
        sink.prune(cutoff)
    }

    /**
     * Blocks until every entry queued so far has been handed to the durable
     * sink (successfully or not) — used by shutdown and tests, never by the
     * hot [record] path.
     *
     * @param timeoutMs maximum time to wait.
     */
    fun flush(timeoutMs: Long = 2_000L) {
        val latch = CountDownLatch(1)
        runCatching { queue.put(Barrier(latch)) }
            .onFailure { return }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Flushes pending writes, then stops the writer thread; the sink itself
     * is not owned by [AuditLog] and is closed independently (it may share a
     * connection pool with addon storage).
     */
    fun close() {
        flush()
        writer.interrupt()
        runCatching { writer.join(1_000) }
    }

    private fun enqueue(entry: AuditEntry) {
        if (!queue.offer(Entry(entry))) {
            // Bounded queue is full — a slow/unreachable sink is falling
            // behind. Drop the oldest pending write to make room for the
            // newest rather than blocking every caller of record().
            queue.poll()
            if (!queue.offer(Entry(entry))) {
                // Lost the race against the writer draining concurrently;
                // give up on this one, it is already effectively dropped.
                droppedSinceLastWarning.incrementAndGet()
                return
            }
            droppedSinceLastWarning.incrementAndGet()
            warnOverflow()
        }
    }

    private fun warnOverflow() {
        val now = clock()
        val last = lastOverflowWarnEpochMs.get()
        if (now - last < OVERFLOW_WARN_INTERVAL_MS) {
            return
        }
        if (lastOverflowWarnEpochMs.compareAndSet(last, now)) {
            val dropped = droppedSinceLastWarning.getAndSet(0)
            logger.warn(
                "Audit write queue is full — dropped {} pending entry/entries; the durable sink is falling behind",
                dropped,
            )
        }
    }

    private fun drainLoop() {
        while (true) {
            val item = try {
                queue.take()
            } catch (_: InterruptedException) {
                return
            }
            when (item) {
                is Entry -> runCatching { sink.append(item.value) }
                    .onFailure { logger.warn("Could not write audit entry to the durable sink: {}", it.message) }
                is Barrier -> item.latch.countDown()
            }
        }
    }

    private sealed interface WorkItem
    private data class Entry(val value: AuditEntry) : WorkItem
    private data class Barrier(val latch: CountDownLatch) : WorkItem

    private companion object {
        /** Minimum gap between overflow warnings, to avoid log spam under sustained overload. */
        const val OVERFLOW_WARN_INTERVAL_MS = 30_000L

        /** Milliseconds in a day, used to turn [retentionDays] into a cutoff. */
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
