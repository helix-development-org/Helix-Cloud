package org.helix.api.execution

/**
 * Backend that executes a service.
 */
enum class ExecutorType {
    /** Service runs as a local child process of the node. */
    PROCESS,

    /** Service runs as a container in the Helix docker network. */
    DOCKER,
}
