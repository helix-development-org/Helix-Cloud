package org.helix.api.addon

import kotlinx.serialization.Serializable

/**
 * Contents of the `addon.json` inside an HXA package.
 *
 * @property id unique addon id, for example `helix.example`.
 * @property name human readable addon name.
 * @property version addon version string.
 * @property main fully qualified class implementing [HelixAddon].
 * @property description one-line summary shown in listings.
 * @property permissions all permission nodes this addon implements/checks,
 *  fed into the network-wide permission catalog (used by the permissions
 *  panel for selectable grants).
 */
@Serializable
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val main: String,
    val description: String = "",
    val permissions: List<String> = emptyList(),
)
