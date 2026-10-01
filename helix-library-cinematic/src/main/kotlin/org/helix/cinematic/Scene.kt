package org.helix.cinematic

/**
 * A sequence of [Shot]s, built with [scene]. Shots come from a fixed list or a generator (endless scenes like
 * the AFK camera); [loop] repeats a fixed list.
 *
 * @property name name for logs.
 * @property transition default transition into every shot.
 * @property loop whether a fixed shot list starts over.
 * @property endTransition transition back to the player's own view (null: cut).
 */
class Scene internal constructor(
    val name: String,
    val transition: Transition,
    val loop: Boolean,
    val endTransition: Transition?,
    private val shots: List<Shot>,
    private val generator: ((index: Int) -> Shot?)?,
) {
    /**
     * @param index running shot number (0 = first).
     * @return the shot, or null when the scene is over.
     */
    fun shot(index: Int): Shot? = when {
        generator != null -> generator.invoke(index)
        shots.isEmpty() -> null
        loop -> shots[index % shots.size]
        else -> shots.getOrNull(index)
    }
}

/**
 * Builder of a [Scene].
 *
 * @property name scene name.
 */
@CinematicDsl
class SceneBuilder internal constructor(private val name: String) {
    /** Default transition into every shot. */
    var transition: Transition = Transition.FADE

    /** Whether the shots start over at the end. */
    var loop: Boolean = false

    /** Transition back to the player's own view (null: cut). */
    var endTransition: Transition? = Transition.FADE

    private val shots = mutableListOf<Shot>()
    private var generator: ((Int) -> Shot?)? = null

    /**
     * Adds a shot.
     *
     * @param ticks duration in ticks (20 = one second).
     * @param block shot settings.
     */
    fun shot(ticks: Int, block: ShotBuilder.() -> Unit) {
        shots += ShotBuilder(ticks).apply(block).build()
    }

    /**
     * Makes the scene endless (or as long as [next] returns shots): [next] is asked for every shot, right when the
     * shot before it starts. Inside, `shot(ticks) { … }` builds and returns a shot.
     *
     * @param next creates shot number `index`, or null to end the scene.
     */
    fun generate(next: ShotGenerator.(index: Int) -> Shot?) {
        generator = { index -> ShotGenerator.next(index) }
    }

    internal fun build() = Scene(name, transition, loop, endTransition, shots.toList(), generator)
}

/**
 * Receiver of [SceneBuilder.generate]: builds shots that are returned instead of added.
 */
@CinematicDsl
object ShotGenerator {
    /**
     * @param ticks duration in ticks.
     * @param block shot settings.
     * @return the shot.
     */
    fun shot(ticks: Int, block: ShotBuilder.() -> Unit): Shot = ShotBuilder(ticks).apply(block).build()
}

/**
 * Builds a [Scene].
 *
 * ```
 * val intro = scene("intro") {
 *     shot(160) {
 *         path = CameraPath.orbit(Target.of(player), startAngle = 0.0, sweep = 60.0, radius = 12.0..5.0, height = 4.0..1.0)
 *         look = Look.at(Target.of(player))
 *         title(20, Component.text("Welcome"))
 *     }
 * }
 * ```
 *
 * @param name scene name.
 * @param block scene settings and shots.
 * @return the scene.
 */
fun scene(name: String, block: SceneBuilder.() -> Unit): Scene = SceneBuilder(name).apply(block).build()

/**
 * Builds a single [Shot] outside a scene (e.g. for [SceneBuilder.generate]).
 *
 * @param ticks duration in ticks.
 * @param block shot settings.
 * @return the shot.
 */
fun shot(ticks: Int, block: ShotBuilder.() -> Unit): Shot = ShotBuilder(ticks).apply(block).build()
