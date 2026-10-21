package de.tytoss.iguard.trace

import de.tytoss.iguard.config.DynamicConfig
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * One `/iguard debug` output line.
 *
 * @property viewer receiving viewer (`console` or a player uuid string).
 * @property playerName debugged player.
 * @property checkId failed check.
 * @property violationLevel the check's VL after the failure.
 * @property alertLevel the check's `alert-vl`.
 * @property counted false while the signal cooldown held the failure back.
 * @property text the rendered line (without prefix or colours).
 */
data class DebugLine(
    val viewer: String,
    val playerName: String,
    val checkId: String,
    val violationLevel: Double,
    val alertLevel: Double,
    val counted: Boolean,
    val text: String,
)

/**
 * Routes check failures of debugged players to their viewers, including failures far below the
 * alert level, rate-limited per viewer, player and check (`debug.rate-limit-millis`; lines held back
 * by the limit are counted into the next line as `(+n)`).
 *
 * @property dynamic live configuration (rate limit).
 */
class DebugRouter(private val dynamic: AtomicReference<DynamicConfig>) {
    // target -> viewer -> watched checks ([ALL] = every check)
    private val watches = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Set<String>>>()
    private val lastLineAt = ConcurrentHashMap<String, Long>()
    private val suppressed = ConcurrentHashMap<String, Int>()

    /** Receives the produced lines; the plugin delivers them on the main thread. */
    @Volatile var output: (DebugLine) -> Unit = {}

    /**
     * Enables or disables debug output of [target] for [viewer].
     *
     * @param viewer `console` or the viewer's uuid string.
     * @param target debugged player.
     * @param check a check id, or null for every check.
     * @param enabled true to watch, false to stop watching (null check + false = stop everything).
     * @return the checks the viewer now watches for the target ([ALL] = every check).
     */
    fun set(viewer: String, target: UUID, check: String?, enabled: Boolean): Set<String> {
        val selection = check ?: ALL
        val viewers = watches.computeIfAbsent(target) { ConcurrentHashMap() }
        val updated = viewers.compute(viewer) { _, current ->
            val checks = current.orEmpty()
            val next = when {
                enabled && selection == ALL -> setOf(ALL)
                enabled -> (checks - ALL) + selection
                selection == ALL -> emptySet()
                else -> checks - selection
            }
            next.ifEmpty { null }
        }
        if (viewers.isEmpty()) watches.remove(target, viewers)
        return updated.orEmpty()
    }

    /** True when [viewer] currently watches [check] (or every check) of [target]. */
    fun isWatching(viewer: String, target: UUID, check: String?): Boolean {
        val checks = watches[target]?.get(viewer) ?: return false
        return ALL in checks || (check != null && check in checks)
    }

    /** True when anybody watches [target] (hot path). */
    fun watching(target: UUID): Boolean = watches[target]?.isNotEmpty() == true

    /** Drops every watch of [viewer] (viewer quit). */
    fun removeViewer(viewer: String) {
        watches.values.forEach { it.remove(viewer) }
        watches.entries.removeIf { it.value.isEmpty() }
    }

    /** Drops every watch on [target] (debugged player quit). */
    fun removeTarget(target: UUID) {
        watches.remove(target)
    }

    /** Produces the debug lines for one frame trace. */
    fun route(trace: FrameTrace) {
        val viewers = watches[trace.frame.playerId] ?: return
        val failures = trace.results.filter { it.failed }
        if (failures.isEmpty()) return
        val rateLimit = dynamic.get().debug.rateLimitMillis
        val now = trace.frame.receivedAt
        for ((viewer, checks) in viewers) {
            for (result in failures) {
                if (ALL !in checks && result.checkId !in checks) continue
                val key = "$viewer|${trace.frame.playerId}|${result.checkId}"
                val last = lastLineAt[key]
                if (last != null && now - last in 0 until rateLimit) {
                    suppressed.merge(key, 1, Int::plus)
                    continue
                }
                lastLineAt[key] = now
                val skipped = suppressed.remove(key) ?: 0
                val evidence = result.evidence.entries.joinToString(" ") { "${it.key}=${it.value}" }
                val text = buildString {
                    append(trace.playerName).append(' ').append(result.checkId)
                    append(String.format(Locale.ROOT, " VL %.2f/%.1f", result.violationLevel, result.alertLevel))
                    if (result.counted) append(String.format(Locale.ROOT, " +%.2f", result.weight)) else append(" (held)")
                    if (skipped > 0) append(" (+").append(skipped).append(')')
                    if (evidence.isNotEmpty()) append(" | ").append(evidence)
                }
                output(DebugLine(viewer, trace.playerName, result.checkId, result.violationLevel, result.alertLevel, result.counted, text))
            }
        }
    }

    companion object {
        /** Selection meaning "every check". */
        const val ALL: String = "*"

        /** Viewer id of the server console. */
        const val CONSOLE: String = "console"
    }
}
