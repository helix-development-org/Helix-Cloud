package org.helix.addons.guardlab.paper

import io.papermc.paper.scoreboard.numbers.NumberFormat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Live IGuard numbers per player for the debug sidebar, fed with IGuard's frame reports.
 *
 * [record] runs on IGuard's worker threads, [view] on the main thread; every player state is guarded by its
 * own lock. Rates are those of the last complete second.
 *
 * @property clock wall clock in epoch millis (tests inject their own).
 */
class LiveStats(private val clock: () -> Long = System::currentTimeMillis) {
    /**
     * The most recent failed check of a player.
     *
     * @property check check id.
     * @property weight violation weight the failure added (0 while the check's cooldown held it back).
     * @property level the check's violation level after the frame.
     * @property alertLevel the check's alert level.
     * @property counted the failure raised the violation level.
     * @property evidence the failure's evidence.
     * @property at epoch millis of the frame.
     */
    data class Flag(
        val check: String,
        val weight: Double,
        val level: Double,
        val alertLevel: Double,
        val counted: Boolean,
        val evidence: Map<String, String>,
        val at: Long,
    )

    /**
     * What the sidebar shows of one player.
     *
     * @property framesPerSecond frame reports in the last complete second.
     * @property failsPerSecond failed check verdicts in the last complete second.
     * @property exemptions readable exemption reasons of the latest checked frame (empty when none or stale).
     * @property tags environment tags of the latest frame.
     * @property lastFlag the most recent failure, null before the first one.
     */
    data class View(
        val framesPerSecond: Int,
        val failsPerSecond: Int,
        val exemptions: List<String>,
        val tags: Set<String>,
        val lastFlag: Flag?,
    )

    private class PlayerState {
        var second = 0L
        var frames = 0
        var fails = 0
        var lastFrames = 0
        var lastFails = 0
        var exemptions: List<String> = emptyList()
        var exemptionsAt = 0L
        var tags: Set<String> = emptySet()
        var flag: Flag? = null
    }

    private val players = ConcurrentHashMap<UUID, PlayerState>()

    /**
     * Records one frame report (thread-safe).
     *
     * @param frame the frame.
     */
    fun record(frame: FrameSample) {
        val now = clock()
        val state = players.computeIfAbsent(frame.playerId) { PlayerState() }
        synchronized(state) {
            roll(state, now)
            state.frames++
            val failures = frame.results.filter(CheckSample::failed)
            state.fails += failures.size
            if (frame.type in ExemptionReasons.CHECKED_FRAMES) {
                state.exemptions = frame.exemptions.map(ExemptionReasons::label).distinct()
                state.exemptionsAt = now
            }
            state.tags = frame.tags
            failures.maxWithOrNull(compareBy<CheckSample>({ it.counted }, { it.weight }))?.let { failure ->
                state.flag = Flag(failure.check, failure.weight, failure.level, failure.alertLevel, failure.counted, failure.evidence, now)
            }
        }
    }

    /**
     * The sidebar view of a player.
     *
     * @param playerId the player.
     * @return the view, null before IGuard reported a frame of the player.
     */
    fun view(playerId: UUID): View? {
        val state = players[playerId] ?: return null
        val now = clock()
        return synchronized(state) {
            roll(state, now)
            View(
                framesPerSecond = state.lastFrames,
                failsPerSecond = state.lastFails,
                exemptions = if (now - state.exemptionsAt <= STALE_MS) state.exemptions else emptyList(),
                tags = state.tags,
                lastFlag = state.flag,
            )
        }
    }

    /**
     * Drops a player's state (quit).
     *
     * @param playerId the player.
     */
    fun forget(playerId: UUID) {
        players.remove(playerId)
    }

    private fun roll(state: PlayerState, now: Long) {
        val second = now / 1000
        if (second == state.second) return
        if (second == state.second + 1) {
            state.lastFrames = state.frames
            state.lastFails = state.fails
        } else {
            state.lastFrames = 0
            state.lastFails = 0
        }
        state.frames = 0
        state.fails = 0
        state.second = second
    }

    private companion object {
        /** Exemption reasons older than this are no longer shown (no checked frame since). */
        const val STALE_MS = 1_000L
    }
}

/**
 * One violation level in the sidebar.
 *
 * @property check check id.
 * @property level current level.
 * @property alert the check's alert level.
 */
data class LevelLine(val check: String, val level: Double, val alert: Double)

/**
 * Everything one sidebar shows.
 *
 * @property run the running suite, null without a run.
 * @property ping the viewer's ping in ms.
 * @property tps server TPS (last minute).
 * @property live the viewer's live IGuard numbers, null before IGuard saw the viewer.
 * @property levels the viewer's violation levels.
 * @property iguard false when IGuard is missing (no frame reports at all).
 * @property now epoch millis of the refresh.
 */
data class SidebarInput(
    val run: LiveRun?,
    val ping: Int,
    val tps: Double,
    val live: LiveStats.View?,
    val levels: List<LevelLine>,
    val iguard: Boolean,
    val now: Long,
)

/**
 * Builds the debug sidebar text (MiniMessage, at most 15 lines) – pure, so it can be tested without a server.
 */
object SidebarLines {
    /** Sidebar title. */
    const val TITLE: String = "<#ffcf4a><b>IGuard</b> <#f1e2c2>Live-Debug"

    /** Bukkit renders at most this many sidebar lines. */
    const val MAX_LINES: Int = 15

    /** Violation levels shown (highest first). */
    const val LEVEL_LINES: Int = 3

    /** Longest value shown in one line before it is cut (keeps the sidebar narrow). */
    const val MAX_VALUE: Int = 26

    private const val LABEL = "<#a89a84>"
    private const val VALUE = "<#f1e2c2>"
    private const val DIM = "<#6e6252>"
    private const val OK = "<#4ac85e>"
    private const val WARN = "<#ffb43a>"
    private const val BAD = "<#ff5a4a>"

    /**
     * The sidebar lines.
     *
     * @param input what to show.
     * @return MiniMessage lines, top to bottom, at most [MAX_LINES].
     */
    fun build(input: SidebarInput): List<String> {
        val lines = mutableListOf<String>()
        lines += runLines(input.run)
        lines += ""
        lines += "${LABEL}Ping $VALUE${input.ping} ms $LABEL· TPS ${tpsColor(input.tps)}${fmt(input.tps, 1)}"
        val live = input.live
        if (!input.iguard) {
            lines += "${BAD}IGuard fehlt – keine Frame-Daten"
        } else {
            val fails = live?.failsPerSecond ?: 0
            lines += "${LABEL}Frames $VALUE${live?.framesPerSecond ?: 0}/s $LABEL· Fails ${if (fails > 0) BAD else VALUE}$fails/s"
        }
        val exemptions = live?.exemptions.orEmpty()
        lines += "${LABEL}Exempt " + if (exemptions.isEmpty()) "$VALUE–" else WARN + cut(exemptions.joinToString(", "))
        val tags = live?.tags.orEmpty().sorted()
        lines += "${LABEL}Tags $VALUE" + if (tags.isEmpty()) "–" else cut(tags.joinToString(", "))
        lines += ""
        lines += levelLines(input.levels)
        lines += flagLines(live?.lastFlag, input.now)
        return lines.take(MAX_LINES)
    }

    private fun runLines(run: LiveRun?): List<String> {
        if (run == null) return listOf("${LABEL}Kein Lauf aktiv", "$DIM/guardlab run \\<suite>")
        val counts = buildString {
            append(" $OK✔").append(run.counts[Status.PASS] ?: 0)
            append(" $WARN⚠").append(run.counts[Status.WARN] ?: 0)
            append(" $BAD✖").append(run.counts[Status.FAIL] ?: 0)
            run.counts[Status.GAP]?.let { append(" <#5ab4ff>◇").append(it) }
            run.counts[Status.SKIP]?.let { append(" $DIM»").append(it) }
        }
        val scenario = run.scenario
        val head = "${LABEL}Suite $VALUE${cut(run.suite, 10)} $LABEL${run.done}/${run.total}$counts"
        if (scenario == null) return listOf(head, "${LABEL}Pause $DIM– Werte klingen ab")
        val cheat = when {
            run.cheat == "none" -> "$VALUE–"
            run.cheatOn -> "$BAD${cut(run.cheat, 14)} AN"
            else -> "$VALUE${cut(run.cheat, 14)} ${DIM}aus"
        }
        val lag = if (run.lag == "none") "$VALUE–" else WARN + cut(run.lag, 10)
        val progress = if (run.durationTicks > 0) " $LABEL${run.playTick}/${run.durationTicks}" else ""
        val setbacks = if (run.setbacks > 0) " $LABEL· SB $BAD${run.setbacks}" else ""
        return listOf(
            head,
            "<#ffcf4a>▶ $VALUE${cut(scenario)}",
            "${LABEL}Cheat $cheat $LABEL· Lag $lag",
            "${LABEL}Phase $VALUE${run.phase}$progress$setbacks",
        )
    }

    private fun levelLines(levels: List<LevelLine>): List<String> {
        val raised = levels.filter { it.level >= MIN_LEVEL }.sortedByDescending { it.level }.take(LEVEL_LINES)
        if (raised.isEmpty()) return listOf("${LABEL}VL ${OK}alle 0")
        return raised.map { line ->
            val color = when {
                line.level >= line.alert -> BAD
                line.level >= line.alert / 2 -> WARN
                else -> VALUE
            }
            "$color${cut(shortCheck(line.check), 18)} $VALUE${fmt(line.level, 1)}$DIM/${fmt(line.alert, 0)}"
        }
    }

    private fun flagLines(flag: LiveStats.Flag?, now: Long): List<String> {
        if (flag == null) return listOf("${LABEL}Flag $VALUE–")
        val age = ((now - flag.at).coerceAtLeast(0) / 1000)
        val weight = if (flag.counted) "$BAD+${fmt(flag.weight, 2)}" else "${DIM}(Cooldown)"
        val head = "${LABEL}Flag $VALUE${cut(shortCheck(flag.check), 16)} $weight $DIM${age}s"
        val evidence = flag.evidence.entries.take(2).joinToString(" ") { (key, value) -> "${key.take(10)}=$value" }
        return if (evidence.isEmpty()) listOf(head) else listOf(head, DIM + cut(evidence, MAX_VALUE + 6))
    }

    private fun tpsColor(tps: Double) = when {
        tps >= 19.5 -> OK
        tps >= 17.0 -> WARN
        else -> BAD
    }

    /**
     * Check id without its category: `movement.speed.a` → `speed.a`.
     *
     * @param check check id.
     * @return the short form.
     */
    fun shortCheck(check: String): String = check.substringAfter('.', check)

    /**
     * Cuts a value to [max] characters (`…` at the end) and escapes MiniMessage tags.
     *
     * @param text the value.
     * @param max longest result.
     * @return the shortened, escaped value.
     */
    fun cut(text: String, max: Int = MAX_VALUE): String {
        val short = if (text.length <= max) text else text.take(max - 1) + "…"
        return MiniMessage.miniMessage().escapeTags(short)
    }

    private fun fmt(value: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", value)

    /** Levels below this count as decayed and are not listed. */
    private const val MIN_LEVEL = 0.05
}

/**
 * The live debug sidebar of the IGuard test server: replaces the network's sidebar with what IGuard sees of
 * the viewer right now (ping, frames, fails, exemptions, environment tags, highest violation levels, the last
 * flag with its evidence) and the state of the running GuardLab suite.
 *
 * The sidebar is drawn as its own objective on the viewer's current scoreboard – the bridge's private board –
 * so the display teams on it (rank prefixes, name colours) and with them the tab list stay untouched; only
 * the sidebar slot moves to this objective and the network board stays hidden. A viewer on the shared main
 * scoreboard (no network board configured) gets a private board that mirrors the main board's teams.
 * Lines are score entries with a custom name (no line teams). Runs only where GuardLab is active.
 *
 * @property plugin the plugin.
 * @property probe IGuard access (frame reports, levels, alert levels).
 * @property run the running suite.
 * @property intervalTicks refresh interval.
 */
class LiveSidebar(
    private val plugin: JavaPlugin,
    private val probe: GuardProbe,
    private val run: () -> LiveRun?,
    private val intervalTicks: Long,
) {
    private class Drawn(val board: Scoreboard) {
        var title: String? = null
        val lines = arrayOfNulls<String>(SidebarLines.MAX_LINES)
        var count = -1
    }

    private val stats = LiveStats()
    private val miniMessage = MiniMessage.miniMessage()
    private val drawn = HashMap<UUID, Drawn>()
    private val ownBoards = HashMap<UUID, Scoreboard>()
    private var listener: AutoCloseable? = null
    private var task: BukkitTask? = null
    private var iguard = false

    /** Subscribes to IGuard's frame reports and starts refreshing. */
    fun start() {
        listener = runCatching { probe.onFrames(stats::record) }
            .onFailure { plugin.logger.warning("GuardLab: Live-Sidebar ohne IGuard-Frame-Berichte: ${it.message}") }
            .getOrNull()
        iguard = listener != null
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { refresh() }, intervalTicks, intervalTicks)
    }

    /** Stops refreshing and removes the sidebar from every board it was drawn on. */
    fun stop() {
        task?.cancel()
        task = null
        runCatching { listener?.close() }
        listener = null
        drawn.values.forEach { runCatching { it.board.getObjective(OBJECTIVE)?.unregister() } }
        drawn.clear()
        val main = plugin.server.scoreboardManager.mainScoreboard
        ownBoards.forEach { (playerId, board) ->
            plugin.server.getPlayer(playerId)?.takeIf { it.scoreboard === board }?.scoreboard = main
        }
        ownBoards.clear()
    }

    /**
     * Forgets a leaving player.
     *
     * @param playerId the player.
     */
    fun forget(playerId: UUID) {
        stats.forget(playerId)
        drawn.remove(playerId)
        ownBoards.remove(playerId)
    }

    private fun refresh() {
        val current = run()
        val tps = plugin.server.tps.firstOrNull() ?: 20.0
        val now = System.currentTimeMillis()
        plugin.server.onlinePlayers.forEach { player ->
            runCatching {
                val levels = probe.levels(player.uniqueId).map { (check, level) -> LevelLine(check, level, probe.alertVl(check)) }
                val input = SidebarInput(current, player.ping, tps, stats.view(player.uniqueId), levels, iguard, now)
                draw(player, SidebarLines.build(input))
            }.onFailure { plugin.logger.fine("GuardLab: Sidebar für ${player.name}: ${it.message}") }
        }
    }

    private fun draw(player: Player, lines: List<String>) {
        val board = boardOf(player)
        var objective = board.getObjective(OBJECTIVE)
        var state = drawn[player.uniqueId]
        if (objective == null || state == null || state.board !== board) {
            objective = objective ?: board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Component.empty()).also {
                it.numberFormat(NumberFormat.blank())
            }
            state = Drawn(board).also { drawn[player.uniqueId] = it }
        }
        // The bridge sets its own objective into the slot when it creates a board – take the slot back.
        if (board.getObjective(DisplaySlot.SIDEBAR)?.name != OBJECTIVE) objective.displaySlot = DisplaySlot.SIDEBAR
        if (state.title != SidebarLines.TITLE) {
            objective.displayName(miniMessage.deserialize(SidebarLines.TITLE))
            state.title = SidebarLines.TITLE
        }
        val renumber = state.count != lines.size
        lines.forEachIndexed { index, text -> drawLine(objective, state, index, text, lines.size, renumber) }
        for (index in lines.size until SidebarLines.MAX_LINES) {
            if (state.lines[index] != null || renumber) {
                objective.getScore(entry(index)).resetScore()
                state.lines[index] = null
            }
        }
        state.count = lines.size
    }

    private fun drawLine(objective: Objective, state: Drawn, index: Int, text: String, size: Int, renumber: Boolean) {
        if (!renumber && state.lines[index] == text) return
        val score = objective.getScore(entry(index))
        if (state.lines[index] != text) score.customName(miniMessage.deserialize(text))
        score.score = size - index
        state.lines[index] = text
    }

    /** The board to draw on: the player's own board, or a private mirror of the main board. */
    private fun boardOf(player: Player): Scoreboard {
        val manager = plugin.server.scoreboardManager
        val main = manager.mainScoreboard
        val current = player.scoreboard
        if (current !== main) {
            if (ownBoards[player.uniqueId] !== current) ownBoards.remove(player.uniqueId)
            return current
        }
        val own = ownBoards.getOrPut(player.uniqueId) { manager.newScoreboard }
        mirrorTeams(main, own)
        player.scoreboard = own
        return own
    }

    /** Copies the main board's teams (rank prefixes, colours, members) onto a private board. */
    private fun mirrorTeams(main: Scoreboard, own: Scoreboard) {
        val names = main.teams.mapTo(HashSet()) { it.name }
        own.teams.filter { it.name !in names }.forEach { it.unregister() }
        main.teams.forEach { source ->
            val target = own.getTeam(source.name) ?: own.registerNewTeam(source.name)
            target.prefix(source.prefix())
            target.suffix(source.suffix())
            if (source.hasColor()) target.color(NamedTextColor.nearestTo(source.color()))
            target.entries.filter { it !in source.entries }.forEach(target::removeEntry)
            source.entries.filter { !target.hasEntry(it) }.forEach(target::addEntry)
        }
    }

    private fun entry(index: Int) = "gl" + index.toString().padStart(2, '0')

    private companion object {
        /** Objective name of the debug sidebar. */
        const val OBJECTIVE = "guardlab_live"
    }
}
