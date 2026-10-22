package org.helix.addons.discord

/**
 * Maps report severity, category and status to Discord forum tags.
 *
 * A tag key is `severity.<key>`, `category.<key>` or `status.<key>` (for example `status.in_progress`). A configured
 * override ([DiscordConfig.bugTags]) wins; otherwise a forum tag is matched by name, case-insensitively, against the
 * German label ([LABELS]) or the English key with `_` as space (`in progress`). Forum admins therefore only need to
 * create tags with these names; no ids have to be copied.
 */
object BugTags {
    /** Severity keys of the feedback addon. */
    val SEVERITIES: List<String> = listOf("minor", "annoying", "blocking", "crash", "exploit")

    /** Category keys of the feedback addon. */
    val CATEGORIES: List<String> = listOf("mineclash", "citybuild", "lobby", "network", "graphics", "other")

    /** Status keys of the feedback addon. */
    val STATUSES: List<String> = listOf("new", "confirmed", "in_progress", "fixed", "closed", "rejected", "duplicate", "not_a_bug")

    /** Statuses that archive and lock the thread. */
    val CLOSING: Set<String> = setOf("closed", "rejected", "duplicate", "not_a_bug")

    /** German tag names per tag key. */
    val LABELS: Map<String, String> = mapOf(
        "severity.minor" to "Kleinigkeit",
        "severity.annoying" to "Störend",
        "severity.blocking" to "Blockierend",
        "severity.crash" to "Absturz",
        "severity.exploit" to "Exploit",
        "category.mineclash" to "MineClash",
        "category.citybuild" to "CityBuild",
        "category.lobby" to "Lobby",
        "category.network" to "Netzwerk",
        "category.graphics" to "Grafik",
        "category.other" to "Sonstiges",
        "status.new" to "Neu",
        "status.confirmed" to "Bestätigt",
        "status.in_progress" to "In Arbeit",
        "status.fixed" to "Behoben",
        "status.closed" to "Geschlossen",
        "status.rejected" to "Abgelehnt",
        "status.duplicate" to "Duplikat",
        "status.not_a_bug" to "Kein Bug",
    )

    /**
     * Resolves one tag key to a tag id.
     *
     * @param key tag key, for example `severity.crash`.
     * @param overrides configured overrides.
     * @param available forum tags as lowercase name to id.
     * @return the tag id or `null` when the forum has no matching tag.
     */
    fun resolve(key: String, overrides: Map<String, String>, available: Map<String, String>): String? {
        overrides[key]?.takeIf { it.isNotBlank() }?.let { return it }
        val names = listOfNotNull(LABELS[key], key.substringAfter('.').replace('_', ' '), key.substringAfter('.'))
        return names.firstNotNullOfOrNull { available[it.lowercase()] }
    }

    /**
     * The tags of a report: severity, category and status (Discord allows at most five per thread).
     *
     * @param report the report.
     * @param overrides configured overrides.
     * @param available forum tags as lowercase name to id.
     * @return resolvable tag ids, in that order, without duplicates.
     */
    fun forReport(report: BugReport, overrides: Map<String, String>, available: Map<String, String>): List<String> =
        listOf("severity.${report.severity}", "category.${report.category}", "status.${report.status}")
            .mapNotNull { resolve(it, overrides, available) }
            .distinct()

    /**
     * Reads severity and category from the tags a tester chose on a new forum post.
     *
     * @param applied applied tag ids.
     * @param overrides configured overrides.
     * @param available forum tags as lowercase name to id.
     * @return `(severity, category)`, defaulting to `minor` and `other`.
     */
    fun classify(applied: List<String>, overrides: Map<String, String>, available: Map<String, String>): Pair<String, String> {
        /** The first key of [keys] whose tag is applied, otherwise [fallback]. */
        fun first(prefix: String, keys: List<String>, fallback: String): String =
            keys.firstOrNull { resolve("$prefix.$it", overrides, available)?.let(applied::contains) == true } ?: fallback
        return first("severity", SEVERITIES, "minor") to first("category", CATEGORIES, "other")
    }
}
