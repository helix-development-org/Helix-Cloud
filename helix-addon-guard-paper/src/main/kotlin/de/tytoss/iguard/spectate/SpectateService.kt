package de.tytoss.iguard.spectate

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.i18n.NodeTranslations
import org.helix.api.message.LegacyToMini
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets a staff member spectate a suspect: stores their state, drops them into spectator mode locked to
 * the target's camera, and restores everything on stop / quit. All operations run on the main thread.
 */
class SpectateService(
    private val plugin: JavaPlugin,
    private val translations: NodeTranslations,
) : Listener {
    private val miniMessage = MiniMessage.miniMessage()

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
                admin.world.uid, admin.location.clone(), admin.gameMode, admin.allowFlight, target.uniqueId,
            )
            admin.gameMode = GameMode.SPECTATOR
        } else {
            existing.targetId = target.uniqueId
        }
        // Free camera from a vantage point behind and above the suspect —
        // never lock the spectator to the player.
        val vantage = target.location.clone().add(
            -target.location.direction.x * 4.0,
            2.5,
            -target.location.direction.z * 4.0,
        )
        vantage.direction = target.location.toVector().add(org.bukkit.util.Vector(0.0, 1.0, 0.0)).subtract(vantage.toVector())
        admin.teleport(vantage)
        val stop = translations.screen(admin.name, locale(admin), "spectate.stop-button")
        admin.sendMessage(chat(admin, "spectate.started", "name" to target.name, "stop" to stop))
        return true
    }

    /** Stop spectating and restore the admin's previous state. */
    fun stop(admin: Player) {
        val session = sessions.remove(admin.uniqueId) ?: return
        admin.spectatorTarget = null
        admin.gameMode = session.gameMode
        admin.allowFlight = session.flying
        admin.teleport(session.location)
        admin.sendMessage(chat(admin, "spectate.stopped"))
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

    private fun locale(player: Player): String = player.locale().language

    private fun chat(player: Player, key: String, vararg params: Pair<String, String>): Component =
        render(translations.text(player.name, locale(player), key, *params))

    private fun render(text: String): Component =
        miniMessage.deserialize(LegacyToMini.translate(text)).decoration(TextDecoration.ITALIC, false)
}
