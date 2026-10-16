package org.helix.addons.mineclash.profile

import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker

/**
 * Pays the network currency (crowns, `helix.economy`) for finished MineClash matches.
 *
 * Every participant receives [perMatch] crowns, members of the winning team [perWin] on top; draws pay
 * only the match share. Bookings go through `eco.api.deposit <uuid> <n> mineclash <ref>` so they show
 * up in the player's ledger with reason `mineclash`. Without the economy addon the calls fail quietly.
 *
 * @property actions node action invoker.
 * @property perMatch supplier of the crowns paid to every participant (read per match, so config edits apply live).
 * @property perWin supplier of the extra crowns for each winner.
 */
class KronenRewards(
    private val actions: ActionInvoker,
    private val perMatch: () -> Long,
    private val perWin: () -> Long,
) {
    /**
     * Pays one match.
     *
     * @param record the finished match.
     * @return total crowns the economy accepted.
     */
    fun pay(record: MatchRecord): Long {
        val match = perMatch().coerceAtLeast(0)
        val win = perWin().coerceAtLeast(0)
        var paid = 0L
        record.players.forEachIndexed { team, uuids ->
            uuids.forEach { uuid ->
                val amount = match + if (record.winnerTeam >= 0 && record.winnerTeam == team) win else 0
                if (amount <= 0) return@forEach
                val result = runCatching {
                    actions.invoke(ActionInvocation("eco.api.deposit", listOf(uuid, amount.toString(), REASON, "match:${record.mode}")))
                }.getOrNull()
                if (result?.success == true) paid += amount
            }
        }
        return paid
    }

    /** Setting keys and the ledger reason. */
    companion object {
        /** Ledger reason of match payouts. */
        const val REASON = "mineclash"

        /** Setting: crowns per finished match. */
        const val PER_MATCH = "kronen.perMatch"

        /** Setting: extra crowns per won match. */
        const val PER_WIN = "kronen.perWin"
    }
}
