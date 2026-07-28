package org.helix.node.control.auth

/**
 * Authenticated caller of the control API.
 *
 * A caller is either the static admin token (bridges, wrappers, bootstrap
 * login), a Minecraft player who signed in via the in-game code flow — the
 * latter is a full admin too when their account holds `helix.admin`, without
 * ever touching the shared static credential — or a bridge process presenting
 * a per-service token minted by [ServiceTokenRegistry], scoped to only its own
 * service's `/internal/` routes.
 *
 * @property name display/audit name (`admin` for the static token, otherwise
 *  the canonical Minecraft name).
 * @property admin whether the caller bypasses per-permission checks, either
 *  via the static token or by holding `helix.admin` on their own session;
 *  always `false` for a per-service token.
 * @property viaStaticToken whether this caller presented the shared static
 *  token rather than a real per-player session; audit attribution falls back
 *  to the generic actor label only for this case, never for a named session.
 * @property serviceId non-null only for a per-service token, the service it
 *  is scoped to — such a caller may act only for this exact service id on
 *  `/internal/` routes, never as another service and never on any other route.
 */
data class PanelPrincipal(
    val name: String,
    val admin: Boolean,
    val viaStaticToken: Boolean = false,
    val serviceId: String? = null,
)
