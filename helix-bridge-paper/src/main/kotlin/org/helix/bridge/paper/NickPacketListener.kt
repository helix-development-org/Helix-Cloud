package org.helix.bridge.paper

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Rewrites the profile name in outgoing PLAYER_INFO_UPDATE packets for
 * nicked players, so the name tag above the player (and everything else a
 * client derives from the profile name) shows the assumed name.
 *
 * The player's own info entry is left untouched: the own name tag is
 * never visible, the tab list shows the composed list name anyway, and
 * the client's chat-session/profile state stays consistent that way.
 *
 * Only referenced when the packetevents plugin is installed (softdepend);
 * the bridge degrades to chat/tab-only nicks without it.
 */
class NickPacketListener(private val nicks: ConcurrentHashMap<UUID, String>) : PacketListenerAbstract() {
    /** Rewritten profile entries since startup (surfaced in the bridge's nick log lines). */
    val rewrites = java.util.concurrent.atomic.AtomicLong()

    override fun onPacketSend(event: PacketSendEvent) {
        if (event.packetType != PacketType.Play.Server.PLAYER_INFO_UPDATE || nicks.isEmpty()) return
        val viewer = event.user.uuid
        val wrapper = WrapperPlayServerPlayerInfoUpdate(event)
        var changed = false
        wrapper.entries.forEach { entry ->
            val profile = entry?.gameProfile ?: return@forEach
            if (profile.uuid == viewer) return@forEach
            val nick = nicks[profile.uuid] ?: return@forEach
            if (profile.name != nick) {
                profile.name = nick
                changed = true
                rewrites.incrementAndGet()
            }
        }
        if (changed) event.markForReEncode(true)
    }
}
