package org.helix.node.services

/**
 * Test handle whose exit is triggered manually.
 */
class FakeHandle : ServiceHandle {
    private var exitCallback: ((Int) -> Unit)? = null

    /** Whether [exit] was called. */
    var exited = false

    /** Whether [stop] was called. */
    var stopCalled = false

    /** Whether [kill] was called. */
    var killCalled = false

    override val alive: Boolean
        get() = !exited

    override fun stop() {
        stopCalled = true
    }

    override fun kill() {
        killCalled = true
    }

    override fun onExit(callback: (Int) -> Unit) {
        exitCallback = callback
    }

    override fun logs(tail: Int): List<String> = listOf("log line")

    /**
     * Simulates process termination.
     *
     * @param code exit code delivered to the manager.
     */
    fun exit(code: Int) {
        exited = true
        exitCallback?.invoke(code)
    }
}
