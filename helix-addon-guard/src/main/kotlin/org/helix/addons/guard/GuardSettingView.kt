package org.helix.addons.guard

import kotlinx.serialization.Serializable

/**
 * One entry of the `guard.config.get` JSON response.
 *
 * @property path dotted config path.
 * @property value current effective value (override or default).
 * @property default default value from IGuard's bundled config.yml.
 * @property type value type id: `string`, `int`, `double` or `boolean`.
 * @property static whether a change requires a service restart.
 * @property overridden whether an override is stored for this path.
 */
@Serializable
data class GuardSettingView(
    val path: String,
    val value: String,
    val default: String,
    val type: String,
    val static: Boolean,
    val overridden: Boolean,
)
