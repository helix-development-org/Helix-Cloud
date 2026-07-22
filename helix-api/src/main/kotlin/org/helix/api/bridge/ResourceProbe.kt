package org.helix.api.bridge

import java.lang.management.ManagementFactory

/**
 * Measures the current JVM's resource usage for heartbeat reports. Used by
 * the bridges, which run inside the server JVM — so the values describe the
 * actual game server (identically for process and docker execution).
 */
object ResourceProbe {
    /**
     * JVM heap currently in use.
     *
     * @return used heap in megabytes.
     */
    fun memoryUsedMb(): Int {
        val runtime = Runtime.getRuntime()
        return ((runtime.totalMemory() - runtime.freeMemory()) / MEGABYTE).toInt()
    }

    /**
     * Maximum heap the JVM may grow to.
     *
     * @return maximum heap in megabytes.
     */
    fun memoryMaxMb(): Int = (Runtime.getRuntime().maxMemory() / MEGABYTE).toInt()

    /**
     * Recent CPU load of this process.
     *
     * @return load in percent (one decimal), or `-1.0` when unavailable.
     */
    fun cpuPercent(): Double {
        val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            ?: return -1.0
        val load = bean.processCpuLoad
        if (load < 0) {
            return -1.0
        }
        return Math.round(load * 1000.0) / 10.0
    }

    private const val MEGABYTE = 1_048_576L
}
