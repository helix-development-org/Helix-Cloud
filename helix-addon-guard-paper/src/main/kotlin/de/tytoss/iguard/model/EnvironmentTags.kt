package de.tytoss.iguard.model

/**
 * The environment tags the main-thread sampler attaches to an [EnvironmentFrame] and how the checks
 * interpret them. Kept in one place so the sampler, the check engine and the evaluator never disagree
 * about which tag exempts what.
 */
object EnvironmentTags {
    /** The player's feet block is a liquid (swimming, wading). */
    const val LIQUID = "liquid"

    /** The feet are in air directly above a liquid block (walking ON water is a Jesus cheat). */
    const val LIQUID_SURFACE = "liquid-surface"

    /** The feet block is a ladder, vine, scaffolding or another climbable block. */
    const val CLIMBABLE = "climbable"

    /** The server runs below the low-TPS threshold. */
    const val LOW_TPS = "low-tps"

    /** The player rides an entity (boat, minecart, horse, seat). */
    const val VEHICLE = "vehicle"

    /** The player glides with an elytra. */
    const val ELYTRA = "elytra"

    /**
     * Context-only tags: they never exempt anything. Low TPS only widens tolerances (exempting on it
     * would disable detection server-wide under load), the liquid surface is where the Jesus check runs.
     */
    val NON_EXEMPTING: Set<String> = setOf(LOW_TPS, LIQUID_SURFACE)

    /**
     * Tags under which a player cannot be judged for combat, world or inventory actions at all.
     * Physics tags (flight, vehicle, liquid, elytra …) deliberately do not belong here: a seated or
     * flying player still attacks, builds and mines with normal reach and pacing.
     */
    val ACTION_EXEMPTING: Set<String> = setOf("bypass", "dead", "sleeping", "game-mode")

    /** The tags that exempt the dry-land movement checks (everything but context tags and [LIQUID]). */
    fun movementExempting(tags: Set<String>): Set<String> =
        tags.filterTo(LinkedHashSet()) { it !in NON_EXEMPTING && it != LIQUID }

    /** True when any tag changes the movement physics (anything but the context-only tags). */
    fun physicsModified(tags: Set<String>): Boolean = tags.any { it !in NON_EXEMPTING }

    /** True when combat, world and inventory checks must be skipped. */
    fun actionExempt(tags: Set<String>): Boolean = tags.any { it in ACTION_EXEMPTING }
}
