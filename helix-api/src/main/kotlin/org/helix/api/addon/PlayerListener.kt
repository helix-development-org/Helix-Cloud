package org.helix.api.addon

import org.helix.api.player.OnlinePlayer

/**
 * Receives network-wide player join and leave events.
 *
 * Registered by addons; the proxy bridges report joins and leaves to the
 * node, which fans them out to all listeners.
 */
interface PlayerListener {
    /**
     * Called when a player joined the network.
     *
     * @param player the joined player.
     */
    fun onJoin(player: OnlinePlayer) {
    }

    /**
     * Called when a player left the network.
     *
     * @param player the left player.
     */
    fun onLeave(player: OnlinePlayer) {
    }
}
