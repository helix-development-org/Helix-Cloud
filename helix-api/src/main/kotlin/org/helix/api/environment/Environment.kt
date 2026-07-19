package org.helix.api.environment

/**
 * Server platform a service runs on.
 *
 * The environment decides which server jar is downloaded, which bridge is
 * injected and whether the service acts as a proxy in front of backends.
 *
 * @property proxy whether services of this environment route players to
 *   backend services instead of hosting gameplay themselves.
 */
enum class Environment(val proxy: Boolean) {
    /** Paper Minecraft server, hosts gameplay behind a proxy. */
    PAPER(proxy = false),

    /** Velocity proxy, routes players to backend services. */
    VELOCITY(proxy = true),
}
