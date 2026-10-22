package org.helix.addons.cinematic.paper

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import java.time.Duration

/**
 * The AFK screen: the whole screen dimmed (a translucent full-screen glyph of the HelixCinematic pack, font
 * `helix_cinematic:afk`) with a big headline and a hint below – shown as title, refreshed while the player is
 * idle and cleared on the first input.
 */
object AfkScreen {
    private val FONT = Key.key("helix_cinematic", "afk")

    /**
     * Five translucent squares between two half-width back steps: net advance ≈ 0, so the overlay is centred on the
     * headline that follows and covers even ultra-wide screens.
     */
    private const val OVERLAY = ""

    /**
     * Shows (or refreshes) the screen.
     *
     * @param player the idle player.
     * @param settings texts.
     * @param fadeIn whether to fade in (first show) or keep it steady (refresh).
     */
    fun show(player: Player, settings: CinematicSettings, fadeIn: Boolean) {
        val mm = MiniMessage.miniMessage()
        val title = Component.text(OVERLAY).font(FONT).append(mm.deserialize(settings.afkTitle))
        player.showTitle(
            Title.title(
                title,
                mm.deserialize(settings.afkSubtitle),
                Title.Times.times(ticks(if (fadeIn) FADE_TICKS else 0), ticks(STAY_TICKS), ticks(FADE_TICKS)),
            ),
        )
    }

    /**
     * Hides the screen.
     *
     * @param player the player.
     */
    fun hide(player: Player) = player.clearTitle()

    private fun ticks(n: Int): Duration = Duration.ofMillis(n * 50L)

    /** Interval in which a shown screen is refreshed (before its stay time runs out). */
    const val REFRESH_TICKS = 100

    private const val FADE_TICKS = 15
    private const val STAY_TICKS = 140
}
