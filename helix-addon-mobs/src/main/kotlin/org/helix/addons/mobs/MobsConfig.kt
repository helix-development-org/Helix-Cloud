package org.helix.addons.mobs

import kotlinx.serialization.Serializable

/**
 * Configuration of the mobs addon, persisted under the `mobs` storage key.
 *
 * @property hintEnabled whether joining players get the client-mod hint at all.
 * @property hintOnce `true` shows the hint only on a player's very first
 *   network join (remembered in addon storage); `false` shows it on every
 *   network join.
 */
@Serializable
data class MobsConfig(
    val hintEnabled: Boolean = true,
    val hintOnce: Boolean = true,
)
