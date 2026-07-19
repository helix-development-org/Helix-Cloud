package org.helix.api.action

import kotlinx.serialization.Serializable

/**
 * Describes a registered action.
 *
 * @property name unique action name, dot-separated, for example `service.start`.
 * @property description one-line human readable summary.
 * @property usage argument hint, for example `service.start <task>`.
 */
@Serializable
data class ActionDescriptor(
    val name: String,
    val description: String,
    val usage: String,
)
