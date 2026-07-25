package org.helix.addons.guard

/**
 * One panel-editable IGuard config value.
 *
 * @property path dotted config path, for example `alerts.cooldown-millis`.
 * @property segments the path split into YAML nesting segments; check ids
 *   keep their inner dots as one segment, for example
 *   `["checks", "movement.fly.a", "enabled"]`.
 * @property type value type used for validation and YAML rendering.
 * @property default default value copied from IGuard's bundled config.yml.
 * @property static whether services must be restarted to pick up a change;
 *   dynamic values are hot-reloadable via `iguard reload`.
 */
data class GuardSetting(
    val path: String,
    val segments: List<String>,
    val type: GuardValueType,
    val default: String,
    val static: Boolean,
)
