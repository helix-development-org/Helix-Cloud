package org.helix.bridge.velocity

import kotlinx.serialization.Serializable

/**
 * The MOTD configuration published by the MOTD addon as the `motd.config`
 * bridge value: a normal and a maintenance profile.
 *
 * @property normal profile served during regular operation.
 * @property maintenance profile served while network maintenance is on.
 */
@Serializable
data class MotdData(
    val normal: MotdProfileData = MotdProfileData(),
    val maintenance: MotdProfileData = MotdProfileData(),
)
