package org.helix.api.platform

import kotlinx.serialization.Serializable

/**
 * Control-API performance over a recent rolling window.
 *
 * @property avgMs average response time in milliseconds.
 * @property p95Ms 95th-percentile response time in milliseconds.
 * @property requestsPerMinute request rate over the recent window.
 * @property errorRate share of `4xx`/`5xx` responses, in percent.
 * @property totalRequests all-time request count since node start.
 */
@Serializable
data class ApiStats(
    val avgMs: Double = 0.0,
    val p95Ms: Double = 0.0,
    val requestsPerMinute: Double = 0.0,
    val errorRate: Double = 0.0,
    val totalRequests: Long = 0,
)
