package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * Result of a proxy long-poll: everything a proxy bridge needs to react
 * to immediately.
 *
 * @property commands commands to execute now (kick/message/broadcast).
 * @property routingVersion current routing version; when it differs from
 *   the value the bridge sent, the bridge re-fetches the routing snapshot.
 * @property commandCatalogVersion current player-command catalog version;
 *   when it differs, the bridge re-registers player-commands.
 * @property ackToken cursor the bridge must echo back as `ackUpTo` on its
 *   NEXT poll to confirm [commands] were received; the node only removes
 *   them from the queue on that confirmation, so a response lost in transit
 *   (proxy restart, connection reset) never silently drops a command. Equals
 *   the previously-sent token when this poll carried no new commands.
 */
@Serializable
data class ProxyPoll(
    val commands: List<ProxyCommand> = emptyList(),
    val routingVersion: Int = 0,
    val commandCatalogVersion: Int = 0,
    val ackToken: Long = 0,
)
