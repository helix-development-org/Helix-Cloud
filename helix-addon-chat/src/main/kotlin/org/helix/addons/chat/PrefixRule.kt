package org.helix.addons.chat

import kotlinx.serialization.Serializable

/**
 * A prefix rule: players with the permission get the prefix and color.
 *
 * Rules are evaluated in list order; the first matching rule wins, so the
 * most important rank belongs at the top.
 *
 * @property permission permission node identifying the rank.
 * @property prefix chat/tab prefix with `&` colors, for example `&cAdmin &f`.
 * @property color name color code, for example `&c`.
 */
@Serializable
data class PrefixRule(
    val permission: String,
    val prefix: String,
    val color: String = "&f",
)
