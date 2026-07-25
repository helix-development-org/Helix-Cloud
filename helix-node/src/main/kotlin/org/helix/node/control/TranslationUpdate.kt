package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Request body to set, create or reset a translation value.
 *
 * @property key flat translation key, for example
 *   `helix.translations.velocity.screen.maintenance`.
 * @property language language code the value belongs to.
 * @property value new template; ignored when [reset] is true.
 * @property reset remove the custom value, restoring the declared default.
 */
@Serializable
data class TranslationUpdate(
    val key: String,
    val language: String,
    val value: String = "",
    val reset: Boolean = false,
)
