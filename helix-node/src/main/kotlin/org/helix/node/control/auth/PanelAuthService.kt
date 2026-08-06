package org.helix.node.control.auth

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import org.helix.api.proxy.PermissionCheckRequest
import org.helix.node.gates.PermissionService
import org.helix.node.players.PlayerRegistry

/**
 * Web-panel authentication and authorization.
 *
 * Login works by Minecraft account: a player requests a code, receives it
 * in-game via [deliver], and exchanges it for a session token. Every access
 * is gated by the permission addon — both the initial sign-in
 * ([NodeConfig.ControlSettings.loginPermission][org.helix.node.config.NodeConfig.ControlSettings.loginPermission])
 * and each dashboard view. The static admin token stays valid as a bootstrap
 * login and for bridge/wrapper machine auth and bypasses permission checks.
 *
 * @property adminToken static token that grants unrestricted access.
 * @property loginPermission permission required to sign in at all.
 * @property loginMessage in-game message template; `{code}` is substituted.
 * @property codeTtlMs lifetime of an issued login code.
 * @property sessionTtlMs absolute lifetime of a minted session, regardless of
 *  activity.
 * @property idleTtlMs a session also expires after this long without any
 *  authenticated request, independent of [sessionTtlMs].
 * @property players online-player registry used to reach and identify players.
 * @property permissions permission service backing every permission check.
 * @property deliver sends a message to a player in-game, returning whether it
 *  was dispatched.
 * @property clock epoch-millis source, injectable for tests.
 */
class PanelAuthService(
    private val adminToken: String,
    private val loginPermission: String,
    private val loginMessage: String,
    private val codeTtlMs: Long,
    private val sessionTtlMs: Long,
    private val idleTtlMs: Long = DEFAULT_IDLE_TTL_MS,
    private val players: PlayerRegistry,
    private val permissions: PermissionService,
    private val deliver: (String, String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<String, PendingLogin>()
    private val sessions = ConcurrentHashMap<String, PanelSession>()
    private val lastCodeAt = ConcurrentHashMap<String, Long>()

    /**
     * Issues a login code for an online, permitted player and sends it in-game.
     *
     * @param rawName the entered Minecraft name.
     * @return a challenge acknowledgement.
     * @throws IllegalArgumentException if the player is offline, lacks the
     *  login permission, or the code could not be delivered. The offline and
     *  lacks-permission cases share one message on purpose — an
     *  unauthenticated caller must not be able to tell "offline" from "online
     *  but not allowed" and enumerate which online players hold panel access.
     */
    @Synchronized
    fun requestCode(rawName: String): ChallengeResponse {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "name must not be empty" }
        val player = players.find(name)
        require(player != null) { LOGIN_CODE_DENIED_MESSAGE }
        require(grantsPermission(player.name, loginPermission)) { LOGIN_CODE_DENIED_MESSAGE }
        val now = clock()
        evictStaleLoginState(now)
        val previous = lastCodeAt[player.name.lowercase()]
        require(previous == null || now - previous >= CODE_COOLDOWN_MS) {
            "please wait a moment before requesting another code"
        }
        lastCodeAt[player.name.lowercase()] = now
        val code = "%06d".format(random.nextInt(1_000_000))
        pending[player.name.lowercase()] = PendingLogin(
            name = player.name,
            uuid = player.uuid,
            code = code,
            expiresAtMs = clock() + codeTtlMs,
        )
        val sent = deliver(player.name, loginMessage.replace("{code}", code))
        require(sent) { "could not deliver the code to ${player.name} in-game" }
        return ChallengeResponse(true, "A login code was sent to ${player.name} in-game.")
    }

    /**
     * Verifies a code and, on success, mints a session token.
     *
     * @param rawName the Minecraft name being verified.
     * @param rawCode the code entered in the panel.
     * @return the session token and the caller's identity.
     * @throws IllegalArgumentException if no code was requested, it expired,
     *  too many attempts were made, or the code is wrong.
     */
    @Synchronized
    fun verify(rawName: String, rawCode: String): SessionResponse {
        val key = rawName.trim().lowercase()
        val record = pending[key]
        requireNotNull(record) { "no login code was requested for this player" }
        if (clock() >= record.expiresAtMs) {
            pending.remove(key)
            throw IllegalArgumentException("the login code has expired")
        }
        if (record.attempts >= MAX_ATTEMPTS) {
            pending.remove(key)
            throw IllegalArgumentException("too many attempts, request a new code")
        }
        if (rawCode.trim() != record.code) {
            pending[key] = record.copy(attempts = record.attempts + 1)
            throw IllegalArgumentException("incorrect code")
        }
        pending.remove(key)
        val token = newToken()
        val now = clock()
        sessions[token] = PanelSession(record.name, record.uuid, now + sessionTtlMs, lastSeenAtMs = now)
        val principal = PanelPrincipal(record.name, admin = grantsPermission(record.name, ADMIN_PERMISSION))
        return SessionResponse(token, identity(principal))
    }

    /**
     * Resolves a presented bearer token to a principal.
     *
     * Every call re-checks that the session's player still holds the login
     * permission and refreshes the idle timer, so a demoted or banned staff
     * member loses panel access on their very next request instead of
     * waiting out the session's absolute TTL, and an idle session dies even
     * if it has not yet hit that TTL.
     *
     * Runs on every authenticated request, so only the session-map lookup
     * and mutation hold this service's monitor — the permission checks,
     * which may hit a slow resolver, run outside it. Otherwise one slow
     * check would serialize the entire control API behind this method.
     *
     * @param presented the bearer token from the request.
     * @return the caller, or `null` if the token is unknown, expired, idle
     *  too long, or the player no longer holds the login permission.
     */
    fun authenticate(presented: String): PanelPrincipal? {
        if (presented.isNotEmpty() && presented == adminToken) {
            return PanelPrincipal("admin", admin = true, viaStaticToken = true)
        }
        val session = synchronized(this) {
            val current = sessions[presented] ?: return null
            val now = clock()
            if (now >= current.expiresAtMs || now >= current.lastSeenAtMs + idleTtlMs) {
                sessions.remove(presented)
                return null
            }
            val refreshed = current.copy(lastSeenAtMs = now)
            sessions[presented] = refreshed
            refreshed
        }
        if (!grantsPermission(session.name, loginPermission)) {
            synchronized(this) { sessions.remove(presented) }
            return null
        }
        return PanelPrincipal(session.name, admin = grantsPermission(session.name, ADMIN_PERMISSION))
    }

    /**
     * Revokes every active session belonging to a player, for example after a
     * demotion or ban, without waiting for the session to expire naturally.
     *
     * @param rawName the Minecraft name whose sessions to drop.
     * @return the number of sessions that were revoked.
     */
    @Synchronized
    fun revokeSessions(rawName: String): Int {
        val key = rawName.trim().lowercase()
        val tokens = sessions.filterValues { it.name.lowercase() == key }.keys
        tokens.forEach(sessions::remove)
        return tokens.size
    }

    /**
     * Invalidates a session token (sign-out).
     *
     * @param presented the token to drop.
     */
    fun logout(presented: String) {
        sessions.remove(presented)
    }

    /**
     * Whether a caller may use a specific permission node.
     *
     * @param principal the authenticated caller.
     * @param node the permission node to test.
     * @return `true` for the admin token or when a resolver grants the node.
     */
    fun grants(principal: PanelPrincipal, node: String): Boolean =
        principal.admin || grantsPermission(principal.name, node)

    /**
     * The dashboard views a caller is allowed to open.
     *
     * @param principal the authenticated caller.
     * @return ids of the permitted built-in views.
     */
    fun allowedViews(principal: PanelPrincipal): List<String> =
        VIEW_NODES.filterValues { grants(principal, it) }.keys.toList()

    /**
     * Builds the identity payload for a caller.
     *
     * @param principal the authenticated caller.
     * @return the caller's name, admin flag and allowed views.
     */
    fun identity(principal: PanelPrincipal): PanelIdentity =
        PanelIdentity(principal.name, principal.admin, allowedViews(principal))

    /**
     * Drops expired login codes and elapsed request cooldowns, called on
     * every code request — otherwise [pending] and [lastCodeAt] would grow
     * with every player name ever entered into the login form and never
     * shrink again.
     */
    private fun evictStaleLoginState(now: Long) {
        pending.entries.removeIf { now >= it.value.expiresAtMs }
        lastCodeAt.entries.removeIf { now - it.value >= CODE_COOLDOWN_MS }
    }

    private fun grantsPermission(name: String, node: String): Boolean =
        permissions.check(PermissionCheckRequest(name = name, permission = node))

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Permission nodes and view ids of the built-in dashboard. */
    companion object {
        /** Maximum wrong-code attempts before a code is invalidated. */
        private const val MAX_ATTEMPTS = 5

        /** Minimum delay between login-code requests for the same player. */
        private const val CODE_COOLDOWN_MS = 30_000L

        /** Shared "no" answer for an offline player and an online player
         *  without the login permission — keeps both indistinguishable. */
        const val LOGIN_CODE_DENIED_MESSAGE = "no login code could be issued for that name"

        /** Default idle timeout: 2 hours without a request expires a session. */
        const val DEFAULT_IDLE_TTL_MS = 2 * 60 * 60 * 1000L

        /** Permission node that makes a player's own session a full admin,
         *  equivalent to the static token, without sharing that credential. */
        const val ADMIN_PERMISSION = "helix.admin"

        /** View id to permission node for every built-in dashboard view. */
        val VIEW_NODES: Map<String, String> = linkedMapOf(
            "overview" to "helix.panel.overview",
            "tasks" to "helix.panel.tasks",
            "services" to "helix.panel.services",
            "players" to "helix.panel.players",
            "proxy" to "helix.panel.proxy",
            "events" to "helix.panel.events",
            "logs" to "helix.panel.logs",
            "audit" to "helix.panel.audit",
            "addons" to "helix.panel.addons",
            "schedules" to "helix.panel.schedules",
            "backups" to "helix.panel.backups",
            "files" to "helix.panel.files",
            "translations" to "helix.panel.translations",
            "settings" to "helix.panel.settings",
        )

        /** Permission node prefix for an addon-contributed dashboard panel. */
        fun panelNode(panelId: String): String = "helix.panel.addon.$panelId"
    }
}
