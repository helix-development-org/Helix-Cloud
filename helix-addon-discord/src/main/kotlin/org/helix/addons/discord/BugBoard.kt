package org.helix.addons.discord

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields

/**
 * Schedule of the weekly tester leaderboard post (plan 1 section 8).
 */
object BugBoard {
    /**
     * The ISO week key (`2026-W43`) of an instant.
     *
     * @param now the instant.
     * @param zone time zone.
     * @return the week key.
     */
    fun weekKey(now: Instant, zone: ZoneId): String {
        val date = now.atZone(zone).toLocalDate()
        return "%d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }

    /**
     * Whether the leaderboard is due: the configured weekday and hour have been reached this week and it was not
     * posted this week yet.
     *
     * @param now current instant.
     * @param zone time zone.
     * @param day weekday 1 (Monday) … 7 (Sunday).
     * @param hour hour 0–23.
     * @param lastWeek week key of the last post, if any.
     * @return `true` when a post should be sent now.
     */
    fun due(now: Instant, zone: ZoneId, day: Int, hour: Int, lastWeek: String?): Boolean {
        val local = now.atZone(zone)
        val target = DayOfWeek.of(day.coerceIn(1, 7))
        val reached = local.dayOfWeek > target || (local.dayOfWeek == target && local.hour >= hour.coerceIn(0, 23))
        return reached && weekKey(now, zone) != lastWeek
    }

    /**
     * Renders the leaderboard message.
     *
     * @param entries leaderboard entries, best first.
     * @param title headline.
     * @param empty text when nobody has points yet.
     * @return markdown text.
     */
    fun render(entries: List<BugTopEntry>, title: String, empty: String): String {
        if (entries.isEmpty()) return "## $title\n$empty"
        val medals = listOf("🥇", "🥈", "🥉")
        val lines = entries.mapIndexed { index, entry ->
            val rank = medals.getOrNull(index) ?: "**${entry.rank.takeIf { it > 0 } ?: (index + 1)}.**"
            "$rank ${entry.name} — ${entry.points}"
        }
        return "## $title\n" + lines.joinToString("\n")
    }
}
