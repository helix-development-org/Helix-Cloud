package org.helix.api.addon

import kotlinx.serialization.Serializable
import org.helix.api.action.ActionDescriptor

/**
 * Snapshot of an installed addon for listings.
 *
 * @property manifest the addon manifest.
 * @property state current lifecycle state.
 * @property failureReason why [AddonState.FAILED] was reached, or `null` in
 *  any other state.
 * @property actions the actions this addon registered while enabling —
 *  empty while disabled/failed. Feeds the dashboard's per-addon action
 *  runner (player commands are excluded there client-side, they need an
 *  in-game context).
 */
@Serializable
data class AddonInfo(
    val manifest: AddonManifest,
    val state: AddonState,
    val failureReason: String? = null,
    val actions: List<ActionDescriptor> = emptyList(),
)
