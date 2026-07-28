package org.helix.node.services

/**
 * Control handle of a started service, independent of the executor.
 */
interface ServiceHandle {
    /** Whether the underlying process or container is still running. */
    val alive: Boolean

    /**
     * OS process id of the service, when the executor runs local processes.
     * Persisted so a restarted node can re-adopt surviving services.
     */
    val pid: Long?
        get() = null

    /**
     * OS start instant of the process, epoch millis, when the executor runs
     * local processes and the OS reports it. Persisted alongside [pid] so a
     * restarted node can tell a surviving process apart from an unrelated
     * process that later reused the same pid.
     */
    val startInstantEpochMs: Long?
        get() = null

    /**
     * Requests a graceful stop.
     */
    fun stop()

    /**
     * Terminates the service immediately.
     */
    fun kill()

    /**
     * Registers a callback invoked once when the service exits.
     *
     * @param callback receives the exit code.
     */
    fun onExit(callback: (Int) -> Unit)

    /**
     * Reads the newest log lines of the service.
     *
     * @param tail maximum number of lines from the end.
     * @return the log lines, oldest first.
     */
    fun logs(tail: Int): List<String>

    /**
     * Sends a console command line to the service's standard input.
     *
     * @param line the command, without a trailing newline.
     * @return `true` if the line was delivered; `false` when the executor does
     *  not support console input or the service is not running.
     */
    fun sendCommand(line: String): Boolean = false
}
