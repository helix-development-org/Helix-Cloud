package org.helix.node.services

import java.net.ServerSocket

/**
 * Allocates service ports starting at the task's `startPort`, skipping ports
 * already claimed by other non-terminal Helix services and any port the OS
 * itself refuses to bind (already held by an unrelated process, reserved,
 * still in `TIME_WAIT`, …).
 *
 * @property canBind probes whether a port is actually free; overridable for tests.
 */
class PortAllocator(
    private val canBind: (Int) -> Boolean = ::defaultCanBind,
) {
    /**
     * Finds the first free port.
     *
     * @param startPort lowest port to consider.
     * @param usedPorts ports already claimed by other Helix services.
     * @return the allocated port.
     * @throws IllegalStateException if no bindable port is found up to 65535.
     */
    fun allocate(startPort: Int, usedPorts: Set<Int>): Int {
        var candidate = startPort
        while (candidate in usedPorts || !canBind(candidate)) {
            candidate++
            check(candidate <= MAX_PORT) { "no free port available from $startPort" }
        }
        return candidate
    }

    private companion object {
        /** Highest valid TCP port. */
        const val MAX_PORT = 65535

        /**
         * Confirms a port is actually free by binding a throwaway
         * [ServerSocket] to it and closing it immediately.
         *
         * @param port the candidate port.
         * @return `true` if the bind succeeded.
         */
        fun defaultCanBind(port: Int): Boolean =
            runCatching { ServerSocket(port).close() }.isSuccess
    }
}
