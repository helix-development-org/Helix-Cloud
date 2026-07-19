package org.helix.api.addon

import org.helix.api.proxy.JoinDecision
import org.helix.api.proxy.JoinRequest

/**
 * Checks whether a player may join the network.
 *
 * Join gates are registered by addons (for example a ban addon); proxy
 * bridges ask the node on every login and the node aggregates all gates —
 * the bridges themselves stay addon-agnostic.
 */
fun interface JoinGate {
    /**
     * Evaluates one join attempt.
     *
     * @param request the joining player.
     * @return allow, or deny with a message.
     */
    fun check(request: JoinRequest): JoinDecision
}
