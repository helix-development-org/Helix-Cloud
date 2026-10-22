package org.helix.addons.lobby.paper

/**
 * How one backend appears in the instance picker (design concept §14, CityBuild „Kronstadt“): its pill colour follows
 * the load — green open, gold from [BUSY_PERCENT] percent, red full, grey under maintenance.
 */
enum class InstanceState {
    /** Room to spare. */
    OPEN,

    /** At least [BUSY_PERCENT] percent full. */
    BUSY,

    /** No free slot. */
    FULL,

    /** The mode is under maintenance; not joinable. */
    MAINTENANCE,
    ;

    /** Whether a click may connect to a backend in this state. */
    val joinable: Boolean get() = this == OPEN || this == BUSY
}

/** Load (percent of the cap) from which an instance counts as [InstanceState.BUSY]. */
const val BUSY_PERCENT = 80

/**
 * One row of the instance picker.
 *
 * @property id the service id to connect to (`CityBuild-2`).
 * @property players current player count.
 * @property maxPlayers the backend's player cap (0: unknown, never full).
 * @property state shown state.
 */
data class InstanceRow(
    val id: String,
    val players: Int,
    val maxPlayers: Int,
    val state: InstanceState,
)

/**
 * Pure logic of the instance picker: classification, natural ordering and paging of a task's backends (no Bukkit,
 * unit-tested).
 */
object InstanceList {
    /** Rows per ledger page ([de.tytoss.igui.screen.LedgerLayout.ROWS]). */
    const val PER_PAGE = 8

    /**
     * @param players current player count.
     * @param maxPlayers the cap (0: unknown).
     * @param maintenance whether the mode is under maintenance.
     * @return the state the backend is shown in.
     */
    fun state(players: Int, maxPlayers: Int, maintenance: Boolean): InstanceState = when {
        maintenance -> InstanceState.MAINTENANCE
        maxPlayers > 0 && players >= maxPlayers -> InstanceState.FULL
        maxPlayers > 0 && players * 100 >= maxPlayers * BUSY_PERCENT -> InstanceState.BUSY
        else -> InstanceState.OPEN
    }

    /**
     * Builds the picker rows of one task: every running backend of [task] (case-insensitive), ordered naturally so
     * `CityBuild-2` comes before `CityBuild-10`.
     *
     * @param entries all joinable backends from `lobby.servers`.
     * @param task the task whose instances are listed.
     * @param maintenance whether the mode is under maintenance.
     * @return the rows in display order.
     */
    fun rows(entries: List<ServerEntry>, task: String, maintenance: Boolean): List<InstanceRow> =
        entries.filter { it.task.equals(task, ignoreCase = true) }
            .sortedWith(compareBy<ServerEntry>({ it.id.substringBeforeLast('-').lowercase() }, { index(it.id) }, { it.id }))
            .map { InstanceRow(it.id, it.players, it.maxPlayers, state(it.players, it.maxPlayers, maintenance)) }

    /**
     * @param rows all rows.
     * @return the number of pages (at least one).
     */
    fun pages(rows: List<InstanceRow>): Int = maxOf(1, (rows.size + PER_PAGE - 1) / PER_PAGE)

    /**
     * @param rows all rows.
     * @param page zero-based page, clamped into range.
     * @return the rows on that page.
     */
    fun page(rows: List<InstanceRow>, page: Int): List<InstanceRow> {
        val clamped = page.coerceIn(0, pages(rows) - 1)
        return rows.drop(clamped * PER_PAGE).take(PER_PAGE)
    }

    private fun index(id: String): Int = id.substringAfterLast('-', "").toIntOrNull() ?: Int.MAX_VALUE
}
