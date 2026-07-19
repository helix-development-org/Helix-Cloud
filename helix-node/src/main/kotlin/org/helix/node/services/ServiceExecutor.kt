package org.helix.node.services

/**
 * Execution backend that turns a prepared workspace into a running service.
 */
fun interface ServiceExecutor {
    /**
     * Starts the service.
     *
     * @param spec prepared workspace and start parameters.
     * @return handle controlling the started service.
     */
    fun start(spec: ServiceStartSpec): ServiceHandle
}
