package org.helix.bridge.velocity

import kotlinx.serialization.Serializable

/**
 * One animation frame of the two MOTD lines, as published by the MOTD addon.
 *
 * @property line1 first MOTD line of this frame.
 * @property line2 second MOTD line of this frame.
 */
@Serializable
data class MotdFrameData(
    val line1: String = "",
    val line2: String = "",
)
