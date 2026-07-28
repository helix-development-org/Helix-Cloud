package org.helix.node.services

/**
 * Confirms a live [ProcessHandle] looked up by a persisted pid is actually
 * the same OS process the registry entry describes, not an unrelated
 * process that later reused the pid (for example after a host reboot).
 */
object ProcessIdentity {
    /** Allowed drift, covering OS process tables with second-level resolution. */
    private const val TOLERANCE_MS = 2_000L

    /**
     * Whether [handle] is alive and its OS start instant matches
     * [persistedStartInstantEpochMs] within [TOLERANCE_MS].
     *
     * A missing persisted value or an OS start instant the platform cannot
     * report is treated as "did not survive" — identity can't be verified,
     * so trusting the pid would risk silently adopting a reused process.
     *
     * @param handle the live process handle looked up by the persisted pid.
     * @param persistedStartInstantEpochMs the start instant recorded when
     *  the service was launched, epoch millis.
     * @return `true` if [handle] is confirmed to be the same process.
     */
    fun survived(handle: ProcessHandle, persistedStartInstantEpochMs: Long?): Boolean {
        if (!handle.isAlive || persistedStartInstantEpochMs == null) {
            return false
        }
        val actual = handle.info().startInstant().orElse(null)?.toEpochMilli() ?: return false
        return kotlin.math.abs(actual - persistedStartInstantEpochMs) <= TOLERANCE_MS
    }
}
