package org.helix.api.service

/**
 * Lifecycle state of a service.
 */
enum class ServiceState {
    /** Workspace prepared, service not started yet. */
    PREPARED,

    /** Start requested, server is booting. */
    STARTING,

    /** Bridge reported the service as ready. */
    RUNNING,

    /** Stop requested, server is shutting down. */
    STOPPING,

    /** Service exited normally. */
    STOPPED,

    /** Service exited unexpectedly or failed to start. */
    FAILED,
}
