package org.helix.node.services

/**
 * Test executor recording start specs and exposing controllable handles.
 */
class FakeExecutor : ServiceExecutor {
    /** All specs passed to [start]. */
    val started = mutableListOf<ServiceStartSpec>()

    /** Handles created per start, in order. */
    val handles = mutableListOf<FakeHandle>()

    override fun start(spec: ServiceStartSpec): ServiceHandle {
        started += spec
        return FakeHandle().also { handles += it }
    }
}
