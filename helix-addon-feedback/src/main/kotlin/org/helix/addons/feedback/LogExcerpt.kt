package org.helix.addons.feedback

import java.time.LocalTime

/**
 * Picks the WARN/ERROR lines of a Paper/Velocity console log that belong to a bug report.
 *
 * Console lines look like `[12:34:56 WARN]: message`. Lines without such a header (stack trace frames,
 * wrapped output) continue the previous entry and are kept together with a kept WARN/ERROR entry. Only
 * entries from the last [minutes] minutes count; a header time later than [now] belongs to the previous day.
 */
object LogExcerpt {
    private val header = Regex("""\[(\d{2}):(\d{2}):(\d{2})\s+([A-Z]+)]""")

    /**
     * Filters [lines] (oldest first).
     *
     * @param lines raw console lines.
     * @param now current local time of the server.
     * @param minutes look-back window.
     * @param maxLines maximum number of returned lines (the newest are kept).
     * @return the relevant lines, oldest first.
     */
    fun filter(lines: List<String>, now: LocalTime, minutes: Int, maxLines: Int): List<String> {
        val windowSeconds = minutes * 60L
        val out = mutableListOf<String>()
        var keeping = false
        for (line in lines) {
            val match = header.find(line.take(HEADER_SEARCH))
            if (match == null) {
                if (keeping) out += line
                continue
            }
            val (h, m, s, level) = match.destructured
            val time = runCatching { LocalTime.of(h.toInt(), m.toInt(), s.toInt()) }.getOrNull()
            keeping = time != null && level in LEVELS && ageSeconds(time, now) <= windowSeconds
            if (keeping) out += line
        }
        return if (out.size > maxLines) out.takeLast(maxLines) else out
    }

    private fun ageSeconds(time: LocalTime, now: LocalTime): Long {
        val diff = now.toSecondOfDay() - time.toSecondOfDay()
        return if (diff >= 0) diff.toLong() else (diff + SECONDS_PER_DAY).toLong()
    }

    private val LEVELS = setOf("WARN", "WARNING", "ERROR", "SEVERE", "FATAL")
    private const val HEADER_SEARCH = 64
    private const val SECONDS_PER_DAY = 86_400
}
