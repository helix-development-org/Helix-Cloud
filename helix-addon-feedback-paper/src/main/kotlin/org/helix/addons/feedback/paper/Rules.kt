package org.helix.addons.feedback.paper

/**
 * What a tester who has not accepted the current tester agreement may still do (pure, unit-tested).
 */
object FreezeRules {
    /** Commands that stay usable while frozen (without the slash, lower case). */
    val ALLOWED_COMMANDS: Set<String> = setOf("testervereinbarung", "testeragreement")

    /**
     * @param commandLine the full command line, with or without leading slash.
     * @return true when the frozen player may run it.
     */
    fun commandAllowed(commandLine: String): Boolean {
        val label = commandLine.trimStart('/').substringBefore(' ').substringAfter(':').lowercase()
        return label in ALLOWED_COMMANDS
    }

    /**
     * Whether a move changes the position (head rotation stays allowed so the book can be read).
     *
     * @param fromX old x.
     * @param fromY old y.
     * @param fromZ old z.
     * @param toX new x.
     * @param toY new y.
     * @param toZ new z.
     * @return true when the move has to be cancelled.
     */
    fun blocksMove(fromX: Double, fromY: Double, fromZ: Double, toX: Double, toY: Double, toZ: Double): Boolean =
        fromX != toX || fromZ != toZ || toY > fromY

    /**
     * @param permissionTester whether the player has `feedback.tester`.
     * @param acceptedVersion the version the player accepted, null for never.
     * @param currentVersion the current agreement version.
     * @param staff whether the player is staff (`feedback.staff`) – staff is never frozen.
     * @return true when the player has to accept the agreement first.
     */
    fun mustAccept(permissionTester: Boolean, acceptedVersion: Int?, currentVersion: Int, staff: Boolean): Boolean =
        permissionTester && !staff && (acceptedVersion == null || acceptedVersion < currentVersion)

    /**
     * Freeze decision when the node may be unreachable: without an answer a tester is frozen as a precaution
     * (fail closed) and the check is retried.
     *
     * @param permissionTester whether the player has `feedback.tester`.
     * @param staff whether the player is staff.
     * @param state the node's answer `(acceptedVersion, currentVersion)`, null when unreachable.
     * @return true when the player has to stay frozen.
     */
    fun frozenWith(permissionTester: Boolean, staff: Boolean, state: Pair<Int?, Int>?): Boolean = when {
        !permissionTester || staff -> false
        state == null -> true
        else -> mustAccept(true, state.first, state.second, false)
    }
}

/**
 * The `/testtools` settings of this server (from `config.yml`).
 *
 * @property flyWorlds worlds in which testers may fly.
 * @property kronenAmount crowns per click on „Test-Kronen holen“.
 * @property kronenDailyLimit network-wide daily limit of test crowns per tester.
 * @property points named test points of this server (name to location).
 */
data class TestToolsConfig(
    val flyWorlds: Set<String> = setOf("lobby", "plots"),
    val kronenAmount: Long = 1000,
    val kronenDailyLimit: Long = 10_000,
    val points: Map<String, TestPoint> = emptyMap(),
)

/**
 * A named test point.
 *
 * @property world world name.
 * @property x x.
 * @property y y.
 * @property z z.
 * @property yaw yaw.
 * @property pitch pitch.
 */
data class TestPoint(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

/** Rules of the test tools (pure, unit-tested). */
object TestToolRules {
    /** Valid test point names. */
    private val pointName = Regex("[a-zA-Z0-9_-]{1,24}")

    /**
     * @param testphase the node's `testphase` setting.
     * @param hasPermission whether the player has `tester.tools`.
     * @return true when the test tools may be opened.
     */
    fun available(testphase: Boolean, hasPermission: Boolean): Boolean = testphase && hasPermission

    /**
     * @param config the tools config.
     * @param world the player's world.
     * @return true when flying is allowed there.
     */
    fun flyAllowed(config: TestToolsConfig, world: String): Boolean =
        config.flyWorlds.any { it.equals(world, ignoreCase = true) }

    /**
     * Whether a tester may keep the flight the test tools granted (checked on world change and settings updates).
     *
     * @param testphase the node's `testphase` setting.
     * @param hasPermission whether the player has `tester.tools`.
     * @param flyAllowedHere whether the current world allows test flight.
     * @return true when the flight stays.
     */
    fun keepsFlight(testphase: Boolean, hasPermission: Boolean, flyAllowedHere: Boolean): Boolean =
        testphase && hasPermission && flyAllowedHere

    /**
     * @param name a requested test point name.
     * @return true when it is a valid name.
     */
    fun validPointName(name: String): Boolean = pointName.matches(name)

    /** Player time presets of the menu, in ticks; null = reset to the server time. */
    val TIMES: List<Long?> = listOf(1000L, 6000L, 12500L, 18000L, null)
}
