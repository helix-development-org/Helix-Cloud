package org.helix.addons.mineclash.profile

import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker

/**
 * Forwards finished MineClash matches to the network-wide stats addon (`helix.stats`), so MineClash
 * results show up in `/stats`, the stats top lists and seasons like every other game's.
 *
 * Per player (resolved to the name the stats addon keys by): `mineclash.matches` +1,
 * `mineclash.wins` or `mineclash.losses` +1 (draws count only as a match) and `mineclash.roses` by the
 * rose change. Without the stats addon the calls fail quietly; the match history is kept either way.
 *
 * @property actions node action invoker.
 * @property nameOf resolves a player uuid to the current name, or `null` if unknown.
 */
class StatsReporter(
    private val actions: ActionInvoker,
    private val nameOf: (String) -> String?,
) {
    /**
     * Reports one match.
     *
     * @param record the finished match.
     * @return number of stat updates the stats addon accepted.
     */
    fun report(record: MatchRecord): Int {
        var accepted = 0
        record.players.forEachIndexed { team, uuids ->
            uuids.forEach { uuid ->
                val name = nameOf(uuid) ?: return@forEach
                val updates = buildList {
                    add(MATCHES to 1L)
                    when {
                        record.winnerTeam < 0 -> Unit
                        record.winnerTeam == team -> add(WINS to 1L)
                        else -> add(LOSSES to 1L)
                    }
                    record.rosesDelta[uuid]?.takeIf { it != 0 }?.let { add(ROSES to it.toLong()) }
                }
                updates.forEach { (stat, delta) ->
                    val result = runCatching { actions.invoke(ActionInvocation("stats.add", listOf(stat, name, delta.toString()))) }.getOrNull()
                    if (result?.success == true) accepted++
                }
            }
        }
        return accepted
    }

    /** Stat keys written by [report]. */
    companion object {
        /** Finished matches. */
        const val MATCHES = "mineclash.matches"

        /** Won matches. */
        const val WINS = "mineclash.wins"

        /** Lost matches. */
        const val LOSSES = "mineclash.losses"

        /** Net rose change. */
        const val ROSES = "mineclash.roses"
    }
}
