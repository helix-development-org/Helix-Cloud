package de.tytoss.igui.gui

import org.bukkit.Sound
import org.bukkit.entity.Player

/**
 * A sound effect to play for a GUI event, at a fixed volume and pitch.
 *
 * @property sound the Bukkit sound to play.
 * @property volume playback volume.
 * @property pitch playback pitch.
 */
data class GuiSound(
    val sound: Sound,
    val volume: Float = 1.0f,
    val pitch: Float = 1.0f,
) {
    /**
     * Plays this sound to a player, centered on the player themselves.
     *
     * @param player the player to play the sound to.
     */
    fun play(player: Player) {
        player.playSound(player, sound, volume, pitch)
    }
}

/**
 * Sounds played by [de.tytoss.igui.internal.GuiRuntime] for GUI lifecycle
 * events, configured via [de.tytoss.igui.IGuiConfiguration.sounds]. Each is
 * `null` (silent) by default.
 *
 * @property open played when a GUI inventory is opened.
 * @property click played when a bound slot is clicked.
 * @property navigation played when [de.tytoss.igui.gui.GuiClickContext.openPage] switches pages.
 * @property success played when an input request ([de.tytoss.igui.gui.GuiClickContext.chatInput]
 *  and friends) completes successfully.
 * @property error played when a click/page is denied, or an input request times out or is cancelled.
 */
data class GuiSoundConfiguration(
    var open: GuiSound? = null,
    var click: GuiSound? = null,
    var navigation: GuiSound? = null,
    var success: GuiSound? = null,
    var error: GuiSound? = null,
)
