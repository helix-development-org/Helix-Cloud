package org.helix.node.control

import java.util.concurrent.ConcurrentHashMap

/**
 * Fixed-window request limiter keyed by an arbitrary string (the client IP
 * for control-API routes), cheap enough to check on every request without a
 * dedicated ktor plugin.
 *
 * @property limit maximum allowed requests per [windowMs] for one key.
 * @property windowMs length of one window, in milliseconds.
 * @property clock epoch-millis source, injectable for tests.
 */
class RateLimiter(
    private val limit: Int,
    private val windowMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Window(val startedAtMs: Long, val count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    /**
     * Records one request for [key], starting a fresh window once the
     * previous one has elapsed.
     *
     * @param key the rate-limit key, typically a client IP.
     * @return `true` if the request is within the limit; `false` once the
     *  current window's limit is exceeded.
     */
    @Synchronized
    fun allow(key: String): Boolean {
        val now = clock()
        // Opportunistic cleanup: without it the map keeps one entry per client key ever seen
        // (e.g. every IP that ever hit the API) for the lifetime of the node.
        if (windows.size >= CLEANUP_THRESHOLD) {
            windows.entries.removeIf { now - it.value.startedAtMs >= windowMs }
        }
        val current = windows[key]
        if (current == null || now - current.startedAtMs >= windowMs) {
            windows[key] = Window(now, 1)
            return true
        }
        if (current.count >= limit) {
            return false
        }
        windows[key] = current.copy(count = current.count + 1)
        return true
    }

    private companion object {
        /** Map size at which [allow] sweeps out entries whose window elapsed. */
        const val CLEANUP_THRESHOLD = 1_024
    }
}
