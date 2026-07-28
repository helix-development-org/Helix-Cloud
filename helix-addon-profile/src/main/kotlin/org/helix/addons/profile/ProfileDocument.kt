package org.helix.addons.profile

import kotlinx.serialization.Serializable

/**
 * Persisted profile settings.
 *
 * @property values lowercase player name to (`"<owner>:<key>"` to chosen
 *  value) — the flat key keeps the document shape simple and stable even
 *  as new owners/keys are registered over time.
 */
@Serializable
data class ProfileDocument(
    val values: Map<String, Map<String, String>> = emptyMap(),
)
