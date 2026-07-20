package org.helix.addons.tablist

import kotlinx.serialization.Serializable

/**
 * Persisted tab list configuration.
 *
 * @property header header text, `&` colors and `\n` line breaks.
 * @property footer footer text.
 */
@Serializable
data class TablistConfig(
    val header: String = "&6Helix-Cloud",
    val footer: String = "&7{online}&8/&7{max} players",
)
