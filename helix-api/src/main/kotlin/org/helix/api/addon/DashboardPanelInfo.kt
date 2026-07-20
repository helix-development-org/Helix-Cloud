package org.helix.api.addon

import kotlinx.serialization.Serializable

/**
 * Metadata of a dashboard panel without its markup, for listings.
 *
 * @property id panel id.
 * @property title sidebar label.
 * @property icon inner SVG markup or empty.
 */
@Serializable
data class DashboardPanelInfo(
    val id: String,
    val title: String,
    val icon: String,
)
