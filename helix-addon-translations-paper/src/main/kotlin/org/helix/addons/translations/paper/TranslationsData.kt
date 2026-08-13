package org.helix.addons.translations.paper

import kotlinx.serialization.Serializable

/**
 * One translation key with its custom values and declared defaults per
 * language — the shape returned by the node's `helix.translations.view`
 * action (mirrors `org.helix.node.messages.TranslationEntry`).
 *
 * @property key flat translation key (`helix.translations.<owner>.<key>`).
 * @property owner owning addon/subsystem id, for grouping in the list GUI.
 * @property values custom (persisted) values: language code to template.
 * @property defaults declared defaults: language code to template.
 */
@Serializable
data class TranslationEntry(
    val key: String,
    val owner: String = "",
    val values: Map<String, String> = emptyMap(),
    val defaults: Map<String, String> = emptyMap(),
)

/**
 * Full translations view for the in-game editor (mirrors the node's
 * `TranslationsView`).
 *
 * @property languages every configured language code.
 * @property defaultLanguage network-wide fallback language.
 * @property entries every translation key with values and defaults.
 */
@Serializable
data class TranslationsView(
    val languages: List<String> = emptyList(),
    val defaultLanguage: String = "en",
    val entries: List<TranslationEntry> = emptyList(),
)
