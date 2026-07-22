package org.helix.node.platform

import kotlin.math.ceil
import org.helix.api.platform.ApiStats

/**
 * Records control-API response times and computes rolling performance stats
 * (average, p95, request rate, error rate) over a recent time window.
 *
 * @property windowMs length of the rolling window.
 * @property capacity hard cap on retained samples.
 * @property clock epoch-millis source, injectable for tests.
 */
class ApiMetrics(
    private val windowMs: Long = 300_000,
    private val capacity: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Sample(val at: Long, val durationMs: Double, val error: Boolean)

    private val samples = ArrayDeque<Sample>()
    private var total = 0L

    /**
     * Records one completed request.
     *
     * @param durationMs handling time in milliseconds.
     * @param status HTTP status code.
     */
    @Synchronized
    fun record(durationMs: Double, status: Int) {
        total++
        samples.addLast(Sample(clock(), durationMs, status >= 400))
        prune()
    }

    /**
     * Rolling API performance snapshot.
     *
     * @return average, p95, request rate, error rate and total count.
     */
    @Synchronized
    fun snapshot(): ApiStats {
        prune()
        if (samples.isEmpty()) {
            return ApiStats(totalRequests = total)
        }
        val durations = samples.map { it.durationMs }.sorted()
        val avg = durations.average()
        val p95 = durations[(ceil(0.95 * durations.size).toInt() - 1).coerceIn(0, durations.size - 1)]
        val errors = samples.count { it.error }
        val spanMs = (clock() - samples.first().at).coerceAtLeast(1)
        val perMinute = samples.size * 60_000.0 / spanMs
        return ApiStats(
            avgMs = round1(avg),
            p95Ms = round1(p95),
            requestsPerMinute = round1(perMinute),
            errorRate = round1(errors * 100.0 / samples.size),
            totalRequests = total,
        )
    }

    /**
     * Average response time over the last minute, for the metric time-series.
     *
     * @return the average in milliseconds, or `null` if there were no requests.
     */
    @Synchronized
    fun recentAverageMs(): Double? {
        val cutoff = clock() - 60_000
        val recent = samples.filter { it.at >= cutoff }
        return if (recent.isEmpty()) null else round1(recent.map { it.durationMs }.average())
    }

    private fun prune() {
        val cutoff = clock() - windowMs
        while (samples.isNotEmpty() && samples.first().at < cutoff) {
            samples.removeFirst()
        }
        while (samples.size > capacity) {
            samples.removeFirst()
        }
    }

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0
}
