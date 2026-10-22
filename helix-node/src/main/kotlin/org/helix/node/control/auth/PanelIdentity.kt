package org.helix.node.control.auth

import kotlinx.serialization.Serializable

/**
 * The signed-in caller and what they are allowed to see, returned by
 * `GET /auth/me` and embedded in the verify response.
 *
 * @property name display/audit name of the caller.
 * @property admin whether the caller has unrestricted (admin token) access.
 * @property views ids of the built-in dashboard views the caller may open.
 * @property capabilities ids of the granted [PanelCapabilities] (`services`, `services.console` …): the
 *   dashboard hides controls the caller lacks, the server enforces them anyway.
 */
@Serializable
data class PanelIdentity(
    val name: String,
    val admin: Boolean,
    val views: List<String>,
    val capabilities: List<String> = emptyList(),
)
