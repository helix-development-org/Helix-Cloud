package org.helix.node.control.auth

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-service control-API tokens minted for bridge processes.
 *
 * The node used to hand every managed Paper/Velocity process the same
 * static admin token via `HELIX_CONTROL_TOKEN`, so any plugin on any game
 * server could read it and gain full node-admin access. Instead, each
 * service gets its own random token, scoped to only the `/internal/`
 * bridge routes for its own service id — a compromised game server can no
 * longer create tasks, read configs or reach any other service.
 *
 * Tokens live in memory only and are re-minted on every service launch,
 * which matches services already getting fresh environment variables on
 * every start; nothing needs to survive a node restart.
 */
class ServiceTokenRegistry {
    private val random = SecureRandom()
    private val serviceIdByToken = ConcurrentHashMap<String, String>()
    private val tokenByServiceId = ConcurrentHashMap<String, String>()

    /**
     * Mints a fresh token for a service, invalidating any token previously
     * minted for the same service id.
     *
     * @param serviceId the service the token is scoped to.
     * @return the new token.
     */
    fun mint(serviceId: String): String {
        revoke(serviceId)
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        tokenByServiceId[serviceId] = token
        serviceIdByToken[token] = serviceId
        return token
    }

    /**
     * Resolves a presented token to the service id it is scoped to.
     *
     * @param token the presented bearer token.
     * @return the owning service id, or `null` if the token is unknown.
     */
    fun serviceIdFor(token: String): String? = serviceIdByToken[token]

    /**
     * Invalidates a service's token, for example once it is known to have
     * stopped, so a captured old token cannot be replayed against whatever
     * service id gets reused next.
     *
     * @param serviceId the service whose token to drop.
     */
    fun revoke(serviceId: String) {
        tokenByServiceId.remove(serviceId)?.let { serviceIdByToken.remove(it) }
    }
}
