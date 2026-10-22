package org.helix.api.theme

import kotlinx.serialization.Serializable

/**
 * What a Paper or Velocity bridge receives from `GET /api/v1/internal/theme`: the theme manifest without the
 * dashboard-only parts, plus a content hash that changes whenever the theme changes.
 *
 * @property hash content hash of the installed theme (`neutral` without theme).
 * @property theme the manifest; [ThemeView.NEUTRAL]'s manifest without theme.
 */
@Serializable
data class ThemeSnapshot(val hash: String, val theme: ThemeManifest) {
    /**
     * A view on this snapshot (tokens resolved over the neutral defaults).
     *
     * @return the view; content lookups are not available on bridges.
     */
    fun view(): ThemeView = ThemeView.of(theme, hash)
}
