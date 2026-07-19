package org.helix.node.gates

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.helix.api.addon.JoinGate
import org.helix.api.proxy.JoinDecision
import org.helix.api.proxy.JoinRequest
import org.slf4j.LoggerFactory

/**
 * Aggregates all join gates registered by addons.
 *
 * Evaluation is deny-first: the first gate that rejects wins. A gate that
 * throws is skipped (fail-open) so one broken addon cannot lock the whole
 * network out.
 */
class JoinGateRegistry {
    private val logger = LoggerFactory.getLogger(JoinGateRegistry::class.java)
    private val gates = ConcurrentHashMap<String, CopyOnWriteArrayList<JoinGate>>()

    /**
     * Registers a gate under an owner id.
     *
     * @param owner owning addon id, used for cleanup on disable.
     * @param gate evaluated on every join attempt.
     */
    fun register(owner: String, gate: JoinGate) {
        gates.computeIfAbsent(owner) { CopyOnWriteArrayList() }.add(gate)
    }

    /**
     * Removes all gates of an owner.
     *
     * @param owner the owning addon id.
     */
    fun unregisterOwner(owner: String) {
        gates.remove(owner)
    }

    /**
     * Evaluates a join attempt against all gates.
     *
     * @param request the joining player.
     * @return the first denial, or allow when every gate passes.
     */
    fun evaluate(request: JoinRequest): JoinDecision {
        gates.values.flatten().forEach { gate ->
            val decision = runCatching { gate.check(request) }
                .onFailure { logger.error("join gate failed for {}", request.name, it) }
                .getOrNull()
            if (decision != null && !decision.allowed) {
                return decision
            }
        }
        return JoinDecision.allow()
    }
}
