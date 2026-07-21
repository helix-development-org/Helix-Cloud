package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Request body for the panel player actions (`message`, `kick`, `ban`).
 *
 * @property value the message text or the kick/ban reason (may be empty).
 * @property duration optional ban duration such as `7d`, `12h`; `null` or blank
 *  means a permanent ban.
 */
@Serializable
data class PlayerActionRequest(val value: String = "", val duration: String? = null)
