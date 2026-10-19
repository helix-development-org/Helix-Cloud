package org.helix.addons.feedback

/** Report statuses and the allowed transitions between them. */
object Statuses {
    /** Newly reported. */
    const val NEW: String = "new"

    /** Reproduced and accepted by staff. */
    const val CONFIRMED: String = "confirmed"

    /** Being worked on. */
    const val IN_PROGRESS: String = "in_progress"

    /** Fixed, waiting for deployment. */
    const val FIXED: String = "fixed"

    /** Done. */
    const val CLOSED: String = "closed"

    /** Not accepted. */
    const val REJECTED: String = "rejected"

    /** Same as another report. */
    const val DUPLICATE: String = "duplicate"

    /** Works as intended. */
    const val NOT_A_BUG: String = "not_a_bug"

    /** All statuses. */
    val ALL: List<String> = listOf(NEW, CONFIRMED, IN_PROGRESS, FIXED, CLOSED, REJECTED, DUPLICATE, NOT_A_BUG)

    /** Statuses that take back granted points. */
    val REVOKING: Set<String> = setOf(REJECTED, DUPLICATE, NOT_A_BUG)

    private val transitions: Map<String, Set<String>> = mapOf(
        NEW to setOf(CONFIRMED, REJECTED, DUPLICATE, NOT_A_BUG),
        CONFIRMED to setOf(IN_PROGRESS, FIXED, REJECTED, DUPLICATE, NOT_A_BUG),
        IN_PROGRESS to setOf(FIXED, CONFIRMED),
        FIXED to setOf(CLOSED, IN_PROGRESS),
        CLOSED to setOf(IN_PROGRESS),
        REJECTED to setOf(NEW),
        DUPLICATE to setOf(NEW),
        NOT_A_BUG to setOf(NEW),
    )

    /**
     * Whether a report may move from [from] to [to].
     *
     * @param from current status.
     * @param to requested status.
     * @return true when the transition is allowed.
     */
    fun allowed(from: String, to: String): Boolean = transitions[from]?.contains(to) == true

    /**
     * Computes the points a report carries after entering [to].
     *
     * Points are granted when a report without points enters `confirmed` (so a reopened and re-confirmed
     * report earns them again, a confirmed report moving back from `in_progress` does not earn twice) and
     * taken back when the report is rejected, marked duplicate or not a bug. Automatic reports (no
     * reporter) never carry points.
     *
     * @param report the report before the change.
     * @param to the new status.
     * @param table points per severity.
     * @return the new `(points, pointsGranted)` pair.
     */
    fun pointsAfter(report: Report, to: String, table: Map<String, Int>): Pair<Int, Boolean> {
        if (report.reporter?.uuid == null) return 0 to report.pointsGranted
        return when {
            to == CONFIRMED && report.points == 0 -> (table[report.severity] ?: 0) to true
            to in REVOKING -> 0 to report.pointsGranted
            else -> report.points to report.pointsGranted
        }
    }
}
