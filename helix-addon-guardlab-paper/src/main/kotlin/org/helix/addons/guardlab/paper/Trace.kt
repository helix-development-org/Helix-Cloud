package org.helix.addons.guardlab.paper

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedWriter
import java.io.File
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
