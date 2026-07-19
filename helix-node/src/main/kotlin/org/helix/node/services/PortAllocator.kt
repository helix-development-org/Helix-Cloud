package org.helix.node.services

/**
 * Allocates service ports starting at the task's `startPort`, skipping
 * ports already claimed by other non-terminal services.
 */
class PortAllocator {
    /**
     * Finds the first free port.
     *
     * @param startPort lowest port to consider.
     * @param usedPorts ports already claimed.
     * @return the allocated port.
     * @throws IllegalStateException if no port below 65536 is free.
     */
    fun allocate(startPort: Int, usedPorts: Set<Int>): Int {
        var candidate = startPort
        while (candidate in usedPorts) {
            candidate++
            check(candidate <= 65535) { "no free port available from $startPort" }
        }
        return candidate
    }
}
