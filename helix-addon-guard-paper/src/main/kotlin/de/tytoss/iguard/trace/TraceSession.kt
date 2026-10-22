package de.tytoss.iguard.trace

import de.tytoss.iguard.api.IGuardTraceSession
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * One running JSONL recording. Lines are encoded on the caller's thread (the player's check worker)
 * and written by the shared single-threaded [writer], so the file is append-only and in order. A
 * frame whose lines do not fit into the bounded queue is dropped as a whole (counted in the `end`
 * line) and the next frame re-emits its environment, so a replay never sees a frame without the
 * snapshot it was judged against.
 *
 * @property playerId traced player.
 * @property label sanitised recording label.
 * @property file target file.
 * @property startedAt start of the recording.
 */
class TraceSession(
    override val playerId: UUID,
    override val label: String,
    override val file: Path,
    override val startedAt: Instant,
    private val maxBytes: Long,
    private val maxMillis: Long,
    private val queueCapacity: Int,
    private val writer: ExecutorService,
    private val logger: Logger,
    private val clock: () -> Long,
    private val onClosed: (TraceSession) -> Unit,
) : IGuardTraceSession {
    private val open = AtomicBoolean(true)
    private val pending = AtomicInteger()
    private val queuedFrames = AtomicLong()
    private val dropped = AtomicLong()

    // Worker-thread state (one stripe per player): which environment snapshot was written last.
    private var lastEnvironmentAt = Long.MIN_VALUE

    // Writer-thread state.
    private var output: BufferedWriter? = null
    private var bytes = 0L
    private var closed = false
    private var lastFlushAt = 0L

    override val active: Boolean get() = open.get()

    override val frames: Long get() = queuedFrames.get()

    /** Frames dropped because the write queue was full. */
    val droppedFrames: Long get() = dropped.get()

    /** Queues a control line (header, marker, exemption) that is never dropped. */
    fun control(line: String): Boolean {
        if (!open.get()) return false
        pending.incrementAndGet()
        writer.execute { writeLines(listOf(line)) }
        return true
    }

    /**
     * Queues one evaluated frame (plus its environment and attack targets when they changed).
     * Called on the player's check worker thread.
     */
    fun record(trace: FrameTrace) {
        if (!open.get()) return
        if (trace.frame.receivedAt - startedAt.toEpochMilli() > maxMillis) {
            stop("max-seconds")
            return
        }
        val lines = ArrayList<String>(3)
        val environment = trace.environment
        if (environment != null && environment.capturedAt != lastEnvironmentAt) {
            lines += TraceCodec.environmentLine(environment)
        }
        trace.targets.forEach { lines += TraceCodec.targetLine(it) }
        lines += TraceCodec.frameLine(trace)
        if (pending.incrementAndGet() > queueCapacity) {
            pending.decrementAndGet()
            dropped.incrementAndGet()
            lastEnvironmentAt = Long.MIN_VALUE
            return
        }
        if (environment != null) lastEnvironmentAt = environment.capturedAt
        queuedFrames.incrementAndGet()
        writer.execute { writeLines(lines) }
    }

    override fun marker(text: String): Boolean = control(TraceCodec.markerLine(clock(), text.take(MAX_MARKER_LENGTH)))

    override fun stop(): Path? = if (stop("stopped")) file else null

    /**
     * Closes the recording with [reason] (written into the `end` line).
     *
     * @return false when it was already closed.
     */
    fun stop(reason: String): Boolean {
        if (!open.compareAndSet(true, false)) return false
        val end = TraceCodec.endLine(clock(), reason, queuedFrames.get(), dropped.get())
        writer.execute { finish(end) }
        onClosed(this)
        return true
    }

    /** Flushes buffered lines (called periodically on the writer thread). */
    fun flush() {
        writer.execute { runCatching { output?.flush() } }
    }

    private fun writeLines(lines: List<String>) {
        pending.decrementAndGet()
        if (closed) return
        try {
            val out = output ?: openFile()
            lines.forEach { line ->
                out.write(line)
                out.write('\n'.code)
                bytes += line.length + 1L
            }
            val now = clock()
            if (now - lastFlushAt >= FLUSH_INTERVAL_MILLIS) {
                out.flush()
                lastFlushAt = now
            }
        } catch (error: Exception) {
            logger.warning("IGuard trace $file failed: ${error.message}")
            closed = true
            open.set(false)
            runCatching { output?.close() }
            onClosed(this)
            return
        }
        if (bytes >= maxBytes && open.compareAndSet(true, false)) {
            finish(TraceCodec.endLine(clock(), "max-bytes", queuedFrames.get(), dropped.get()))
            onClosed(this)
        }
    }

    private fun finish(end: String) {
        if (closed) return
        closed = true
        runCatching {
            val out = output ?: openFile()
            out.write(end)
            out.write('\n'.code)
            out.close()
        }.onFailure { logger.warning("IGuard trace $file could not be closed: ${it.message}") }
    }

    private fun openFile(): BufferedWriter {
        Files.createDirectories(file.parent)
        val created = Files.newBufferedWriter(
            file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
        output = created
        return created
    }

    private companion object {
        /** Buffered lines reach the disk at least this often. */
        const val FLUSH_INTERVAL_MILLIS = 1_000L

        /** Longest marker text kept. */
        const val MAX_MARKER_LENGTH = 500
    }
}
