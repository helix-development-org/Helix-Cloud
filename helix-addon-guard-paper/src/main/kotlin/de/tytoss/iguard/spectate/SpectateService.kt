package de.tytoss.iguard.spectate

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets a staff member spectate a suspect: stores their state, drops them into spectator mode locked to
 * the target's camera, and restores everything on stop / quit. All operations run on the main thread.
 */
class SpectateService(private val plugin: JavaPlugin) : Listener {
    private data class Session(val world: UUID, val location: Location, val gameMode: GameMode, val flying: Boolean, var targetId: UUID)

    private val sessions = ConcurrentHashMap<UUID, Session>()

    /** True while the admin has an active spectate session. */
    fun isSpectating(admin: UUID) = sessions.containsKey(admin)

    /** Begin (or retarget) spectating [target] as [admin]. Returns false if the target is offline. */
    fun start(admin: Player, target: Player): Boolean {
        if (admin.uniqueId == target.uniqueId) return false
        val existing = sessions[admin.uniqueId]
        if (existing == null) {
            sessions[admin.uniqueId] = Session(
                admin.world.uid, admin.location.clone(), admin.gameMode, admin.allowFlight, target.uniqueId
            )
            admin.gameMode = GameMode.SPECTATOR
        } else {
            existing.targetId = target.uniqueId
        }
        admin.teleport(target.location)
        // Lock the camera to the suspect; the admin can press shift to detach into free-cam.
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (admin.isOnline && admin.gameMode == GameMode.SPECTATOR) admin.spectatorTarget = target
        }, 2L)
        admin.sendMessage(
            Component.text("Now spectating ", NamedTextColor.GRAY)
                .append(Component.text(target.name, NamedTextColor.AQUA))
                .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                .append(Component.text("[Stop]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/iguard unspectate")))
                .append(Component.text(" (shift = free camera)", NamedTextColor.DARK_GRAY))
        )
        return true
    }

    /** Stop spectating and restore the admin's previous state. */
    fun stop(admin: Player) {
        val session = sessions.remove(admin.uniqueId) ?: return
        admin.spectatorTarget = null
        admin.gameMode = session.gameMode
        admin.allowFlight = session.flying
        admin.teleport(session.location)
        admin.sendMessage(Component.text("Stopped spectating.", NamedTextColor.GRAY))
    }

    /** Restore every active spectator (plugin disable). */
    fun stopAll() {
        sessions.keys.toList().forEach { id -> plugin.server.getPlayer(id)?.let(::stop) }
    }

    /** Cleans up sessions when the admin or the spectated target quits. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        // Admin left mid-spectate: drop the session (their state persists in their own player data on
        // rejoin). Target left: detach the camera so the admin is not stuck.
        val player = event.player
        sessions.remove(player.uniqueId)
        sessions.values.filter { it.targetId == player.uniqueId }.forEach { session ->
            sessions.entries.firstOrNull { it.value === session }?.key?.let { adminId ->
                plugin.server.getPlayer(adminId)?.let { it.spectatorTarget = null }
            }
        }
    }
}
