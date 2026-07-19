package org.helix.api.addon

import org.helix.api.display.DisplayProfile

/**
 * Resolves how a player is displayed in chat and tab list.
 *
 * Registered by addons (for example a chat addon); the node asks all
 * resolvers in registration order and the first non-null profile wins.
 */
fun interface DisplayResolver {
    /**
     * Resolves a player's display profile.
     *
     * @param name player name.
     * @return the profile, or `null` when this resolver has no opinion.
     */
    fun resolve(name: String): DisplayProfile?
}
