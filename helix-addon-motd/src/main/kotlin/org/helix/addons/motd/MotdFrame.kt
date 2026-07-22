package org.helix.addons.motd

import kotlinx.serialization.Serializable

/**
 * One animation frame of the two MOTD lines.
 *
 * @property line1 first MOTD line of this frame.
 * @property line2 second MOTD line of this frame.
 */
@Serializable
data class MotdFrame(
    val line1: String = "",
    val line2: String = "",
)
