package org.helix.cinematic

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerCommon
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Empties a viewer's screen for a cinematic: while active, chat, action bar, boss bar and sidebar packets to the
 * viewer are dropped; the current sidebar and boss bars are hidden and shown again afterwards. (Hotbar, hearts,
 * crosshair and hand disappear by themselves in spectator mode; titles stay – fades use them.)
 */
internal class ScreenCleaner {
    private val active = ConcurrentHashMap.newKeySet<UUID>()
    private val hiddenBars = ConcurrentHashMap<UUID, Pair<List<org.bukkit.boss.BossBar>, List<BossBar>>>()
    private var listener: PacketListenerCommon? = null

    /** Registers the packet filter. */
    fun start() {
        listener = PacketEvents.getAPI().eventManager.registerListener(
            /** Drops HUD packets to viewers with an emptied screen. */
            object : PacketListener {
                override fun onPacketSend(event: PacketSendEvent) {
                    val uuid = event.user?.uuid ?: return
                    if (uuid !in active) return
                    if (event.packetType in DROPPED) event.isCancelled = true
                }
            },
            PacketListenerPriority.HIGH,
        )
    }

    /** Unregisters the packet filter. */
    fun stop() {
        listener?.let { PacketEvents.getAPI().eventManager.unregisterListener(it) }
        listener = null
    }

    /**
     * Empties [viewer]'s screen.
     *
     * @param viewer the player.
     */
    fun clear(viewer: Player) {
        if (viewer.uniqueId in active) return
        // erst ausblenden, dann filtern – sonst verschluckt der Filter die Ausblend-Pakete
        val bukkitBars = Bukkit.getBossBars().asSequence().filter { viewer in it.players }.toList()
        bukkitBars.forEach { it.removePlayer(viewer) }
        val adventureBars = viewer.activeBossBars().toList()
        adventureBars.forEach { viewer.hideBossBar(it) }
        hiddenBars[viewer.uniqueId] = bukkitBars to adventureBars
        // Sidebar leeren: Anzeigeplatz ohne Objective
        PacketEvents.getAPI().playerManager.sendPacket(viewer, WrapperPlayServerDisplayScoreboard(SIDEBAR_SLOT, ""))
        active.add(viewer.uniqueId)
    }

    /**
     * Gives [viewer] their HUD back.
     *
     * @param viewer the player.
     */
    fun restore(viewer: Player) {
        if (!active.remove(viewer.uniqueId)) return
        if (!viewer.isOnline) {
            hiddenBars.remove(viewer.uniqueId)
            return
        }
        hiddenBars.remove(viewer.uniqueId)?.let { (bukkit, adventure) ->
            bukkit.forEach { it.addPlayer(viewer) }
            adventure.forEach { viewer.showBossBar(it) }
        }
        viewer.scoreboard.getObjective(DisplaySlot.SIDEBAR)?.let { objective ->
            PacketEvents.getAPI().playerManager.sendPacket(viewer, WrapperPlayServerDisplayScoreboard(SIDEBAR_SLOT, objective.name))
        }
    }

    private companion object {
        /** Display slot id of the sidebar. */
        const val SIDEBAR_SLOT = 1

        /** HUD packets hidden during a cinematic. */
        val DROPPED = setOf(
            PacketType.Play.Server.SYSTEM_CHAT_MESSAGE,
            PacketType.Play.Server.CHAT_MESSAGE,
            PacketType.Play.Server.DISGUISED_CHAT,
            PacketType.Play.Server.ACTION_BAR,
            PacketType.Play.Server.BOSS_BAR,
            PacketType.Play.Server.DISPLAY_SCOREBOARD,
        )
    }
}
