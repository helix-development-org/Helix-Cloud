package org.helix.addons.chat

import kotlinx.serialization.Serializable

/**
 * Persisted chat configuration.
 *
 * @property format chat line format with `{prefix}`, `{color}`, `{name}`,
 *   `{suffix}` and `{message}` placeholders.
 * @property rules prefix rules, first match wins.
 */
@Serializable
data class ChatConfig(
    val format: String = "{prefix}{color}{name}{suffix} &8» &f{message}",
    val rules: List<PrefixRule> = emptyList(),
)
