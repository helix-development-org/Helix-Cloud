package org.helix.bridge.paper

import kotlinx.serialization.Serializable

/**
 * A single sidebar board published by the scoreboard addon inside the
 * `scoreboard.config` bridge value (a task→board map).
 *
 * @property title sidebar objective title, `&` colors / MiniMessage.
 * @property lines sidebar rows top to bottom, `&` colors / MiniMessage,
 *   with `{placeholder}` markers the bridge substitutes per player.
 * @property enabled whether the board is shown at all.
 * @property updateIntervalTicks server ticks between refreshes.
 */
@Serializable
data class ScoreboardData(
    val title: String = "",
    val lines: List<String> = emptyList(),
    val enabled: Boolean = true,
    val updateIntervalTicks: Int = 20,
) {
    /**
     * The refresh interval clamped to a sane minimum of one tick.
     *
     * @return ticks between refreshes, at least `1`.
     */
    fun intervalTicks(): Long = updateIntervalTicks.coerceAtLeast(1).toLong()

    /**
     * The lines trimmed to the Bukkit sidebar maximum of fifteen rows.
     *
     * @return at most fifteen lines.
     */
    fun boundedLines(): List<String> = lines.take(MAX_LINES)

    /** Constants for sidebar rendering. */
    companion object {
        /** Maximum sidebar rows Bukkit renders. */
        const val MAX_LINES = 15
    }
}
