package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Request body to add a language or change the default language.
 *
 * @property language language code, for example `fr`.
 * @property default when true, [language] becomes the network-wide fallback
 *   (it must already exist) instead of being added.
 */
@Serializable
data class LanguageUpdate(
    val language: String,
    val default: Boolean = false,
)
