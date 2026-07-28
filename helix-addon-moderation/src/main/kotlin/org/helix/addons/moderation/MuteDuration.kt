package org.helix.addons.moderation

/**
 * Parses mute duration tokens like `30m`, `12h`, `7d` or `perm`.
 *
 * Deliberately a self-contained copy of the bans addon's identical parser
 * rather than a cross-addon dependency: HXA addons load and deploy
 * independently, so moderation must not require the bans addon to be
 * installed just to parse a duration token.
 */
object MuteDuration {
    private val pattern = Regex("^(\\d+)([smhd])$")

    /**
     * Whether the token is a valid duration or permanence marker.
     *
     * @param token candidate argument.
     * @return `true` for durations and `perm`/`permanent`.
     */
    fun isDurationToken(token: String): Boolean =
        token.equals("perm", ignoreCase = true) ||
            token.equals("permanent", ignoreCase = true) ||
            pattern.matches(token)

    /**
     * Parses a duration token to milliseconds.
     *
     * @param token duration like `7d`; `perm` yields `null`.
     * @return duration in millis, or `null` for permanent.
     * @throws IllegalArgumentException for invalid tokens.
     */
    fun parseMillis(token: String): Long? {
        if (token.equals("perm", ignoreCase = true) || token.equals("permanent", ignoreCase = true)) {
            return null
        }
        val match = requireNotNull(pattern.matchEntire(token)) { "invalid duration: $token (use 30m, 12h, 7d, perm)" }
        val amount = match.groupValues[1].toLong()
        return amount * when (match.groupValues[2]) {
            "s" -> 1_000L
            "m" -> 60_000L
            "h" -> 3_600_000L
            else -> 86_400_000L
        }
    }

    /**
     * Formats a remaining duration human-readably.
     *
     * @param millis remaining milliseconds.
     * @return compact text like `6d 23h` or `59m`.
     */
    fun format(millis: Long): String {
        val totalMinutes = millis / 60_000
        val days = totalMinutes / (60 * 24)
        val hours = totalMinutes / 60 % 24
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
}
