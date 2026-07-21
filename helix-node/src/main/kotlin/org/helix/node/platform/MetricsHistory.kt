package org.helix.node.platform

import org.helix.api.platform.MetricSample

/**
 * Bounded in-memory history of network metric samples for the dashboard
 * graphs. Holds roughly the last [capacity] samples (12 h at a 15 s interval).
 *
 * @property capacity maximum number of retained samples.
 */
class MetricsHistory(private val capacity: Int = 2880) {
    private val samples = ArrayDeque<MetricSample>()

    /**
     * Appends a sample, evicting the oldest beyond the capacity.
     *
     * @param sample the sample to record.
     */
    @Synchronized
    fun record(sample: MetricSample) {
        samples.addLast(sample)
        while (samples.size > capacity) {
            samples.removeFirst()
        }
    }

    /**
     * Returns the most recent samples, oldest first.
     *
     * @param limit maximum number of samples.
     * @return the samples in chronological order.
     */
    @Synchronized
    fun recent(limit: Int): List<MetricSample> = samples.toList().takeLast(limit)
}
