package org.helix.cinematic

import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import java.time.Duration

/**
 * One camera shot of a [Scene].
 *
 * @property ticks duration.
 * @property path where the camera is.
 * @property look where it looks.
 * @property easing progress curve.
 * @property transition how the shot begins (null: the scene's default).
 * @property events timed actions, by tick within the shot.
 */
data class Shot(
    val ticks: Int,
    val path: CameraPath,
    val look: Look,
    val easing: Easing = Easing.SMOOTH,
    val transition: Transition? = null,
    val events: List<Pair<Int, (Player) -> Unit>> = emptyList(),
)

/**
 * Builder of a [Shot] (see [SceneBuilder.shot]).
 *
 * @property ticks duration.
 */
@CinematicDsl
class ShotBuilder internal constructor(private val ticks: Int) {
    /** Where the camera is (required). */
    var path: CameraPath? = null

    /** Where it looks (default: along the path). */
    var look: Look = Look.along()

    /** Progress curve. */
    var easing: Easing = Easing.SMOOTH

    /** How the shot begins (null: the scene's default). */
    var transition: Transition? = null

    private val events = mutableListOf<Pair<Int, (Player) -> Unit>>()

    /**
     * Runs [action] for every viewer [tick] ticks into the shot.
     *
     * @param tick tick within the shot.
     * @param action what to do.
     */
    fun at(tick: Int, action: (Player) -> Unit) {
        events += tick to action
    }

    /**
     * Shows a title [tick] ticks into the shot.
     *
     * @param tick tick within the shot.
     * @param title main line.
     * @param subtitle second line.
     * @param stayTicks how long it stays (plus 10 ticks fade in and out).
     */
    fun title(tick: Int, title: Component, subtitle: Component = Component.empty(), stayTicks: Int = 50) = at(tick) {
        it.showTitle(Title.title(title, subtitle, Title.Times.times(ticks(10), ticks(stayTicks), ticks(10))))
    }

    /**
     * Plays a sound to the viewer [tick] ticks into the shot.
     *
     * @param tick tick within the shot.
     * @param sound the sound.
     */
    fun sound(tick: Int, sound: Sound) = at(tick) { it.playSound(sound) }

    internal fun build(): Shot = Shot(
        ticks = ticks,
        path = requireNotNull(path) { "a shot needs a path" },
        look = look,
        easing = easing,
        transition = transition,
        events = events.toList(),
    )

    private fun ticks(n: Int): Duration = Duration.ofMillis(n * 50L)
}

/** Marks the cinematic DSL so nested builders do not leak into each other. */
@DslMarker
annotation class CinematicDsl
