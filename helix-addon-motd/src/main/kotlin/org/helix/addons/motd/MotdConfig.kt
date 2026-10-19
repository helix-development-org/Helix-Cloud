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
        line1 = "<#ffcf4a><bold>{network}</bold></#ffcf4a> <#7a4a24>·</#7a4a24> <#f1e2c2>The kingdom awaits you",
        line2 = "<#d8382e>»</#d8382e> <#f1e2c2>MineClash <#7a4a24>·</#7a4a24> <#f1e2c2>CityBuild <#7a4a24>·</#7a4a24> " +
            "<#ffcf4a>kronland.de</#ffcf4a>",
    ),
    val maintenance: MotdProfile = MotdProfile(
        line1 = "<#ffcf4a><bold>{network}</bold></#ffcf4a> <#7a4a24>·</#7a4a24> <#5ab4ff>Geschlossene Testphase",
        line2 = "<#d8382e>»</#d8382e> <#f1e2c2>Infos auf <#ffcf4a>kronland.de</#ffcf4a> <#7a4a24>·</#7a4a24> <#f1e2c2>Discord: <#ffcf4a>/discord",
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
