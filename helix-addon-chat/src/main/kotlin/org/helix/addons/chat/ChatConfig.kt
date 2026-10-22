package org.helix.addons.chat

import kotlinx.serialization.Serializable

/**
 * Persisted chat configuration.
 *
 * @property format chat line format with `{prefix}`, `{color}`, `{name}`,
 *   `{suffix}` and `{message}` placeholders.
 */
@Serializable
data class ChatConfig(
    val format: String = "{prefix}{color}{name}{suffix} <#7a4a24>»</#7a4a24> <#f1e2c2>{message}",
)
