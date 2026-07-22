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
        line1 = "<gradient:#8b5cf6:#38bdf8><bold>{network}</bold></gradient>",
        line2 = "<gray>Welcome — <white>{online}<gray>/<white>{max}<gray> players online",
    ),
    val maintenance: MotdProfile = MotdProfile(
        line1 = "<red><bold>{network}</bold> <dark_gray>» <red>Maintenance",
        line2 = "<gray>We are working on the network — check back soon.",
        onlinePlayers = 0,
        maxPlayers = 0,
        versionText = "&cMaintenance",
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
