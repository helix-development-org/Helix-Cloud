package org.helix.node.resources

import java.io.InputStream

/**
 * [InternalResources] reading from the launcher classpath.
 */
class ClasspathInternalResources : InternalResources {
    /**
     * Opens an embedded resource from the classpath.
     *
     * @param name resource path, for example `helix-internal/Wrapper.jar`.
     * @return stream of the resource content.
     * @throws IllegalStateException if the resource does not exist.
     */
    override fun open(name: String): InputStream =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "embedded resource missing: $name (run from Launcher.jar)"
        }
}
