package org.helix.addons.permissions

/**
 * Parses and formats grant durations such as `30s`, `15m`, `12h`, `7d`.
 */
object GrantDuration {
    private val pattern = Regex("^(\\d+)([smhd])$", RegexOption.IGNORE_CASE)

    /**
     * Whether a token looks like a duration.
     *
     * @param token the candidate token.
     * @return `true` for `<number><s|m|h|d>` tokens.
     */
    fun isDurationToken(token: String): Boolean = pattern.matches(token.trim())

    /**
     * Parses a duration token to milliseconds.
     *
     * @param token for example `7d` or `30m`.
     * @return the duration in millis, or `null` for invalid tokens.
     */
    fun parseMillis(token: String): Long? {
        val match = pattern.matchEntire(token.trim()) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unitMs = when (match.groupValues[2].lowercase()) {
            "s" -> 1_000L
            "m" -> 60_000L
            "h" -> 3_600_000L
            else -> 86_400_000L
        }
        return amount * unitMs
    }

    /**
     * Formats a remaining duration human-readably, for example `6d 23h`.
     *
     * @param millis remaining milliseconds.
     * @return the formatted remainder, `expired` when non-positive.
     */
    fun format(millis: Long): String {
        if (millis <= 0) {
            return "expired"
        }
        val days = millis / 86_400_000
        val hours = millis % 86_400_000 / 3_600_000
        val minutes = millis % 3_600_000 / 60_000
        val seconds = millis % 60_000 / 1_000
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }
}
