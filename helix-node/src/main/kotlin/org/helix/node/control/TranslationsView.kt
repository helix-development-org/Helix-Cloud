package org.helix.node.control

import kotlinx.serialization.Serializable
import org.helix.node.messages.TranslationEntry

/**
 * Dashboard payload of the translations page.
 *
 * @property languages every configured language code.
 * @property defaultLanguage network-wide fallback language.
 * @property entries every translation key with values and defaults per
 *   language.
 */
@Serializable
data class TranslationsView(
    val languages: List<String>,
    val defaultLanguage: String,
    val entries: List<TranslationEntry>,
)
