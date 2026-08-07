package org.helix.addons.subtitles.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the Text Display entities rendering players' chosen subtitles.
 *
 * A subtitle is a free-standing, non-persistent [TextDisplay] positioned
 * just below where the vanilla name tag renders, re-teleported every tick
 * to follow the player (rather than attached as a passenger) so it never
 * interferes with the player actually riding a real vehicle.
 */
class SubtitleDisplayService {
    private val displays = ConcurrentHashMap<UUID, TextDisplay>()

    /**
     * Ensures [player] shows [text] as their subtitle, spawning a display
     * entity on first use and only touching its text when it actually
     * changed. Removes the display entirely when [text] is blank.
     *
     * @param player the player to update.
     * @param text the subtitle text, or blank/`null` to remove it.
     */
    fun update(player: Player, text: String?) {
        if (text.isNullOrBlank()) {
            remove(player.uniqueId)
            return
        }
        val display = displays[player.uniqueId]?.takeIf { it.isValid } ?: spawn(player)
        val component = Component.text(text, NamedTextColor.GRAY)
        if (display.text() != component) {
            display.text(component)
        }
    }

    /**
     * Re-teleports every tracked player's subtitle to their current
     * location; called every tick so the text follows moving players.
     *
     * @param onlinePlayers currently connected players, to look up by uuid.
     */
    fun track(onlinePlayers: Collection<Player>) {
        val byUuid = onlinePlayers.associateBy { it.uniqueId }
        displays.forEach { (uuid, display) ->
            if (!display.isValid) {
                displays.remove(uuid)
                return@forEach
            }
            byUuid[uuid]?.let { display.teleport(subtitleLocation(it)) }
        }
    }

    /**
     * Removes a player's subtitle display, if one exists.
     *
     * @param uuid the player's uuid.
     */
    fun remove(uuid: UUID) {
        displays.remove(uuid)?.remove()
    }

    /** Removes every tracked display; called on plugin disable. */
    fun shutdown() {
        displays.keys.toList().forEach(::remove)
    }

    private fun spawn(player: Player): TextDisplay {
        val display = player.world.spawn(subtitleLocation(player), TextDisplay::class.java) { entity ->
            entity.billboard = Display.Billboard.CENTER
            entity.isPersistent = false
            entity.isSeeThrough = false
            entity.isShadowed = true
        }
        displays[player.uniqueId] = display
        return display
    }

    /** Just below where the vanilla name tag renders. */
    private fun subtitleLocation(player: Player): Location = player.location.clone().add(0.0, SUBTITLE_HEIGHT, 0.0)

    private companion object {
        /** Blocks above the player's feet; tuned to sit just under the vanilla name tag. */
        const val SUBTITLE_HEIGHT = 2.1
    }
}
