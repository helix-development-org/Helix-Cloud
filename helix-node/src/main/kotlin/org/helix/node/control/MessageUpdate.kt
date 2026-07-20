package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Request body to update or reset an addon message.
 *
 * @property addonId owning addon id.
 * @property key message key.
 * @property value new template; ignored when [reset] is true.
 * @property reset restore the declared default instead of setting a value.
 */
@Serializable
data class MessageUpdate(
    val addonId: String,
    val key: String,
    val value: String = "",
    val reset: Boolean = false,
)
