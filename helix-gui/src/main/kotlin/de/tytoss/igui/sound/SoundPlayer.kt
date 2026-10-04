package de.tytoss.igui.sound

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import org.bukkit.entity.Player

/** Plays [NetworkSounds] events with the sound source of their category (so the player's volume sliders apply). */
object SoundPlayer {
    /**
     * Plays a network sound to one player only.
     *
     * @param player the listener.
     * @param key sound event key ([NetworkSounds]).
     * @param volume volume.
     * @param pitch pitch.
     */
    fun play(player: Player, key: String, volume: Float = 1f, pitch: Float = 1f) {
        val source = when (NetworkSounds.category(key)) {
            "voice" -> Sound.Source.VOICE
            "neutral" -> Sound.Source.NEUTRAL
            "ambient" -> Sound.Source.AMBIENT
            "music" -> Sound.Source.MUSIC
            else -> Sound.Source.MASTER
        }
        player.playSound(Sound.sound(Key.key(key), source, volume, pitch))
    }
}
