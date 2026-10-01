package org.helix.cinematic

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.time.Duration

/** Why a playback ended. */
enum class FinishReason {
    /** All shots were shown. */
    COMPLETED,

    /** The viewer gave input and the playback was skippable. */
    SKIPPED,

    /** [Playback.stop] was called. */
    STOPPED,

    /** A target a shot depends on is gone. */
    TARGET_LOST,

    /** The viewer left or changed world. */
    QUIT,
}

/**
 * Options of a playback (see [Cinematics.play]).
 */
@CinematicDsl
class PlaybackOptions {
    /** Whether any input of the viewer (moving, looking, chat, clicks …) ends the playback. */
    var skipOnInput: Boolean = true

    /**
     * Whether the viewer becomes a spectator for the playback (invisible to others, no hotbar, crosshair or hand);
     * game mode and position are restored afterwards.
     */
    var spectate: Boolean = false

    /** Whether a look-alike NPC ([StandIn]) stands where the viewer stood – combine with [spectate]. */
    var standIn: Boolean = false

    /** Whether chat, action bar, boss bars and the sidebar are hidden for the playback. */
    var cleanScreen: Boolean = false

    internal var finish: (FinishReason) -> Unit = {}

    /**
     * Called once when the playback ends.
     *
     * @param action receives the reason.
     */
    fun onFinish(action: (FinishReason) -> Unit) {
        finish = action
    }
}

/** Shared runtime of the library handed to playbacks. */
internal class Services(
    val npcs: de.tytoss.inpc.INpc,
    val scope: kotlinx.coroutines.CoroutineScope,
    val cleaner: ScreenCleaner,
)

/**
 * A running [Scene] for one viewer; ticked by [Cinematics].
 *
 * @property viewer who watches.
 * @property scene what is played.
 */
class Playback internal constructor(
    private val plugin: Plugin,
    private val services: Services,
    val viewer: Player,
    val scene: Scene,
    internal val options: PlaybackOptions,
    private val onEnded: (Playback) -> Unit,
) {
    private val rig = CameraRig(plugin, viewer)
    private var index = 0
    private var shot: Shot? = null
    private var shotTick = 0
    private var coverCountdown = 0
    private var pending: Shot? = null
    private var upcoming: Shot? = null
    private var fadeShown = false
    private var finished = false
    private var entered = false
    private var savedMode: org.bukkit.GameMode? = null
    private var savedLocation: org.bukkit.Location? = null
    private var standIn: StandIn? = null

    /** The look-alike NPC of this playback, once the camera took over (see [PlaybackOptions.standIn]). */
    val standInNpc: StandIn? get() = standIn

    /** Whether the playback is still running. */
    val running: Boolean get() = !finished

    internal fun start() {
        val first = scene.shot(0) ?: return finish(FinishReason.COMPLETED)
        queue(first, first.transition ?: scene.transition)
    }

    /**
     * Ends the playback and gives the viewer their view back.
     *
     * @param reason reported to [PlaybackOptions.onFinish].
     */
    fun stop(reason: FinishReason = FinishReason.STOPPED) = finish(reason)

    internal fun tick() {
        if (finished) return
        if (!viewer.isOnline || !rig.valid) return finish(FinishReason.QUIT)
        rig.tick()
        pending?.let { next ->
            if (coverCountdown > 0) coverCountdown--
            if (coverCountdown <= 0 && rig.ready) begin(next)
            return
        }
        val current = shot ?: return
        shotTick++
        current.events.forEach { (at, action) -> if (at == shotTick) runCatching { action(viewer) } }
        val upcoming = upcoming
        val outgoing = if (upcoming != null) upcoming.transition ?: scene.transition else scene.endTransition
        val cover = outgoing?.coverTicks ?: 0
        if (!fadeShown && outgoing is Transition.Fade && shotTick == current.ticks - cover) {
            fade(outgoing)
            fadeShown = true
        }
        if (shotTick >= current.ticks) {
            if (upcoming == null) return finish(FinishReason.COMPLETED)
            index++
            pending = upcoming
            coverCountdown = 0
            fadeShown = false
            return
        }
        if (shotTick % CameraRig.STEP_TICKS == 0) {
            val pose = pose(current, (shotTick + CameraRig.STEP_TICKS).toDouble() / current.ticks) ?: return finish(FinishReason.TARGET_LOST)
            rig.glide(pose.first, pose.second)
        }
    }

    private fun queue(next: Shot, transition: Transition) {
        pending = next
        coverCountdown = transition.coverTicks
        if (transition is Transition.Fade) fade(transition)
    }

    private fun begin(next: Shot) {
        pending = null
        shot = next
        shotTick = 0
        // nächsten Shot genau einmal holen (Generatoren würfeln und prüfen die Sicht)
        upcoming = scene.shot(index + 1)
        val pose = pose(next, 0.0) ?: return finish(FinishReason.TARGET_LOST)
        rig.jump(pose.first, pose.second)
        if (!entered) enter()
        rig.activate()
    }

    /** First cut (the screen is black): spectator, stand-in, empty HUD. */
    private fun enter() {
        entered = true
        if (options.standIn) standIn = StandIn(plugin, services.npcs, services.scope, viewer).also { it.spawn() }
        if (options.spectate) {
            savedMode = viewer.gameMode
            savedLocation = viewer.location.clone()
            viewer.gameMode = org.bukkit.GameMode.SPECTATOR
        }
        if (options.cleanScreen) services.cleaner.clear(viewer)
    }

    private fun exit() {
        if (!entered) return
        if (options.cleanScreen) services.cleaner.restore(viewer)
        if (viewer.isOnline) {
            savedMode?.let { viewer.gameMode = it }
            savedLocation?.let { viewer.teleport(it) }
        }
        standIn?.remove()
        standIn = null
    }

    private fun pose(shot: Shot, progress: Double): Pair<Vec3, Rotation>? {
        val t = shot.easing.ease(progress.coerceIn(0.0, 1.0))
        val position = shot.path.position(t) ?: return null
        val rotation = shot.look.rotation(position, t, shot.path) ?: return null
        return position to rotation
    }

    private fun finish(reason: FinishReason) {
        if (finished) return
        finished = true
        rig.release()
        exit()
        if (viewer.isOnline && reason != FinishReason.COMPLETED) viewer.resetTitle()
        runCatching { options.finish(reason) }.onFailure { plugin.logger.warning("cinematic ${scene.name}: onFinish failed: ${it.message}") }
        onEnded(this)
    }

    private fun fade(fade: Transition.Fade) {
        viewer.showTitle(
            Title.title(
                Component.text(FADE_GLYPHS).font(FADE_FONT),
                Component.empty(),
                Title.Times.times(ticks(fade.inTicks), ticks(fade.holdTicks), ticks(fade.outTicks)),
            ),
        )
    }

    private fun ticks(n: Int): Duration = Duration.ofMillis(n * 50L)

    private companion object {
        /** Font of the HelixCinematic resource pack with a full-screen black glyph (U+E000) and a -1 space (U+E001). */
        val FADE_FONT: Key = Key.key("helix_cinematic", "fade")

        /** Three black glyphs side by side – covers ultra-wide screens too. */
        const val FADE_GLYPHS = ""
    }
}
