package org.helix.node.control.auth

/**
 * Authenticated caller of the control API.
 *
 * A caller is either the static admin token (bridges, wrappers, bootstrap
 * login) or a Minecraft player who signed in via the in-game code flow.
 *
 * @property name display/audit name (`admin` for the static token, otherwise
 *  the canonical Minecraft name).
 * @property admin whether the caller authenticated with the static admin token
 *  and therefore bypasses per-permission checks.
 */
data class PanelPrincipal(val name: String, val admin: Boolean)
