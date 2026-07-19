package org.helix.wrapper

/**
 * Entry point of the universal service wrapper.
 *
 * The wrapper is extracted from `Launcher.jar` into a service workspace,
 * starts the configured server jar, injects the platform bridge and connects
 * the service to the node.
 */
object WrapperMain {
    /**
     * Boots the wrapper inside a service workspace.
     *
     * @param args command line arguments; currently unused.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        println("Helix-Cloud wrapper: service execution not implemented yet.")
    }
}
