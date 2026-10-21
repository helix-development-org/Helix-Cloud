package org.helix.addons.guardlab.paper

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedWriter
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.round

/**
 * One tick of a scenario trace.
 *
 * @property tick playback tick.
 * @property x position x.
 * @property y position y.
 * @property z position z.
 * @property vx server-side velocity x.
 * @property vy server-side velocity y.
 * @property vz server-side velocity z.
 * @property onGround the client's on-ground flag as the server sees it.
 * @property yaw yaw.
 * @property pitch pitch.
 * @property ping ping in ms.
 * @property tps server TPS (1 min).
 * @property rises check → VL rise this tick.
 */
data class TraceSample(
    val tick: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val vx: Double,
    val vy: Double,
    val vz: Double,
    val onGround: Boolean,
    val yaw: Float,
    val pitch: Float,
    val ping: Int,
    val tps: Double,
    val rises: Map<String, Double>,
)

/**
 * Writes a scenario trace as JSON lines: one object per tick plus marker lines (`{"tick":…,"marker":…}`).
 *
 * @property file target file (parent directories are created).
 */
class TraceWriter(private val file: File) : AutoCloseable {
    private val writer: BufferedWriter = file.also { it.parentFile.mkdirs() }.bufferedWriter()

    /**
     * Appends a tick.
     *
     * @param sample the tick.
     */
    fun sample(sample: TraceSample) {
        val line = buildJsonObject {
            put("t", sample.tick)
            put("x", r(sample.x)); put("y", r(sample.y)); put("z", r(sample.z))
            put("vx", r(sample.vx)); put("vy", r(sample.vy)); put("vz", r(sample.vz))
            put("ground", sample.onGround)
            put("yaw", r(sample.yaw.toDouble())); put("pitch", r(sample.pitch.toDouble()))
            put("ping", sample.ping)
            put("tps", r(sample.tps))
            if (sample.rises.isNotEmpty()) putJsonObject("vl") { sample.rises.forEach { (check, rise) -> put(check, r(rise)) } }
        }
        writer.write(line.toString())
        writer.newLine()
    }

    /**
     * Appends a marker.
     *
     * @param tick playback tick.
     * @param marker marker name (`scenario`, `started`, `setback`, `finished`, `pulse`, `knockback`, `spike` …).
     * @param detail optional detail.
     */
    fun marker(tick: Int, marker: String, detail: String = "") {
        val line = buildJsonObject {
            put("t", tick)
            put("marker", marker)
            if (detail.isNotEmpty()) put("detail", detail)
        }
        writer.write(line.toString())
        writer.newLine()
    }

    /** Flushes and closes the file. */
    override fun close() = writer.close()

    private fun r(value: Double): Double = round(value * PRECISION) / PRECISION

    private companion object {
        const val PRECISION = 10_000.0
    }
}

/**
 * Copies IGuard's trace file of a scenario into the report directory.
 *
 * IGuard completes a stopped recording asynchronously; the file is finished once its last line is the
 * `{"type":"end",…}` line. The copy waits for that (bounded) so the report holds the whole recording.
 */
object IGuardTraceCopy {
    private const val TAIL_BYTES = 4096
    private const val POLL_MILLIS = 100L

    /**
     * Whether the trace file is complete.
     *
     * @param file the IGuard trace.
     * @return true when its last line is the `end` line.
     */
    fun finished(file: Path): Boolean {
        if (!Files.isRegularFile(file)) return false
        val tail = RandomAccessFile(file.toFile(), "r").use { raf ->
            val length = raf.length()
            val size = minOf(length, TAIL_BYTES.toLong()).toInt()
            val bytes = ByteArray(size)
            raf.seek(length - size)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
        val last = tail.trimEnd().substringAfterLast('\n')
        return last.contains("\"type\":\"end\"")
    }

    /**
     * Waits until the trace is finished (at most [timeoutMillis]) and copies it.
     *
     * @param source IGuard's trace file.
     * @param target copy in the report directory (parent directories are created).
     * @param timeoutMillis longest wait for the `end` line.
     * @param sleep pause between polls (tests pass a no-op).
     * @return true when a finished trace was copied, false when the copy is incomplete or the source is missing.
     */
    fun copy(source: Path, target: Path, timeoutMillis: Long, sleep: (Long) -> Unit = Thread::sleep): Boolean {
        var waited = 0L
        while (!finished(source) && waited < timeoutMillis) {
            sleep(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        if (!Files.isRegularFile(source)) return false
        target.parent?.let { Files.createDirectories(it) }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        return finished(target)
    }
}
