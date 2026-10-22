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
        line1 = "<tc:accent><bold>{network}</bold></tc:accent> <tc:muted>·</tc:muted> <tc:text>The kingdom awaits you",
        line2 = "<tc:primary>»</tc:primary> <tc:text>MineClash <tc:muted>·</tc:muted> <tc:text>CityBuild <tc:muted>·</tc:muted> " +
            "<tc:accent>kronland.de</tc:accent>",
    ),
    val maintenance: MotdProfile = MotdProfile(
        line1 = "<tc:accent><bold>{network}</bold></tc:accent> <tc:muted>·</tc:muted> <tc:info>Geschlossene Testphase",
        line2 = "<tc:primary>»</tc:primary> <tc:text>Infos auf <tc:accent>kronland.de</tc:accent> <tc:muted>·</tc:muted> <tc:text>Discord: <tc:accent>/discord",
        onlinePlayers = 0,
        maxPlayers = 0,
        versionText = "&bTestphase",
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
