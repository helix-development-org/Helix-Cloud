package org.helix.node.resources

import java.io.InputStream

/**
 * Access to artifacts embedded in `Launcher.jar`, such as the wrapper and
 * the bridge plugins.
 */
fun interface InternalResources {
    /**
     * Opens an embedded resource.
     *
     * @param name resource path, for example `helix-internal/Wrapper.jar`.
     * @return stream of the resource content.
     * @throws IllegalStateException if the resource does not exist.
     */
    fun open(name: String): InputStream
}

