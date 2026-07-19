package org.helix.api.addon

import kotlinx.serialization.Serializable

/**
 * Snapshot of an installed addon for listings.
 *
 * @property manifest the addon manifest.
 * @property state current lifecycle state.
 */
@Serializable
data class AddonInfo(
    val manifest: AddonManifest,
    val state: AddonState,
)
