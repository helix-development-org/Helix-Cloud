package org.helix.addons.motd

import kotlinx.serialization.Serializable

/**
 * The two server-list profiles: `normal` while the network is open and
 * `maintenance` while network maintenance is enabled.
 *
 * @property normal profile served during regular operation.
 * @property maintenance profile served while maintenance mode is on.
 */
@Serializable
data class MotdConfig(
    val normal: MotdProfile = MotdProfile(
        line1 = "<#ffcf4a><bold>{network}</bold></#ffcf4a> <#7a4a24>·</#7a4a24> <#f1e2c2>The arena awaits you",
        line2 = "<#d8382e>»</#d8382e> <#f1e2c2>Cards, villages and tournaments <#7a4a24>·</#7a4a24> " +
            "<#ffcf4a>{online}</#ffcf4a><#7a4a24>/</#7a4a24><#ffcf4a>{max}</#ffcf4a> <#f1e2c2>online",
    ),
    val maintenance: MotdProfile = MotdProfile(
        line1 = "<#ffcf4a><bold>{network}</bold></#ffcf4a> <#7a4a24>·</#7a4a24> <#ffb43a>Maintenance",
        line2 = "<#ffb43a>The villagers are rebuilding the kingdom <#7a4a24>—</#7a4a24> <#ffb43a>check back soon.",
        onlinePlayers = 0,
        maxPlayers = 0,
        versionText = "&6Maintenance",
    ),
) {
    /**
     * Returns the requested profile.
     *
     * @param name `normal` or `maintenance`.
     * @return the profile, or `null` for unknown names.
     */
    fun profile(name: String): MotdProfile? = when (name.lowercase()) {
        "normal" -> normal
        "maintenance" -> maintenance
        else -> null
    }

    /**
     * Returns a copy with the named profile replaced.
     *
     * @param name `normal` or `maintenance`.
     * @param updated the new profile value.
     * @return the updated configuration.
     */
    fun with(name: String, updated: MotdProfile): MotdConfig = when (name.lowercase()) {
        "normal" -> copy(normal = updated)
        else -> copy(maintenance = updated)
    }
}
