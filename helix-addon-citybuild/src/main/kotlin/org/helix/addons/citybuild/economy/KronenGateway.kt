package org.helix.addons.citybuild.economy

import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker

/**
 * Outcome of one Kronen booking.
 *
 * @property ok whether the booking happened.
 * @property code machine readable failure code (`insufficient_funds`, `limit`, `economy_unavailable`,
 *   `error`), `null` on success.
 * @property balance the new balance reported by the economy, when it reported one.
 */
data class MoneyResult(val ok: Boolean, val code: String? = null, val balance: Long? = null) {
    /** Factories for common results. */
    companion object {
        /** A successful booking. */
        val OK: MoneyResult = MoneyResult(true)

        /**
         * A failed booking.
         *
         * @param code failure code.
         * @return the failure.
         */
        fun fail(code: String): MoneyResult = MoneyResult(false, code)
    }
}

/**
 * The network Kronen economy as seen by CityBuild. Every booking carries a reason and a reference
 * so the economy ledger explains each change; players are identified by uuid.
 */
interface KronenGateway {
    /**
     * Credits Kronen.
     *
     * @param player player uuid.
     * @param amount Kronen, `> 0`.
     * @param reason ledger reason, for example `job:miner`.
     * @param ref ledger reference, for example `auction:17`.
     * @param limitKey optional network-wide daily limit counter.
     * @param limitMax maximum of [limitKey] per day.
     * @return the outcome.
     */
    fun deposit(player: String, amount: Long, reason: String, ref: String, limitKey: String? = null, limitMax: Long? = null): MoneyResult

    /**
     * Debits Kronen; fails without change when the balance does not cover [amount].
     *
     * @param player player uuid.
     * @param amount Kronen, `> 0`.
     * @param reason ledger reason.
     * @param ref ledger reference.
     * @return the outcome.
     */
    fun withdraw(player: String, amount: Long, reason: String, ref: String): MoneyResult

    /**
     * Moves Kronen atomically from one player to another. The sender pays [amount], the receiver
     * gets [amount] minus the fee ([feePermille] ‰, rounded down) — the fee leaves the economy.
     *
     * @param from paying player uuid.
     * @param to receiving player uuid.
     * @param amount Kronen, `> 0`.
     * @param reason ledger reason.
     * @param feePermille fee in per mille.
     * @param ref ledger reference.
     * @return the outcome.
     */
    fun transfer(from: String, to: String, amount: Long, reason: String, feePermille: Int, ref: String): MoneyResult
}

/**
 * [KronenGateway] backed by the economy addon's machine API (`eco.api.deposit`, `eco.api.withdraw`,
 * `eco.api.transfer`), invoked node-locally through the action registry.
 *
 * @property actions node action invoker.
 */
class ActionKronenGateway(private val actions: ActionInvoker) : KronenGateway {
    /** Calls `eco.api.deposit <player> <amount> <reason> <ref> [limitKey limitMax]`. */
    override fun deposit(player: String, amount: Long, reason: String, ref: String, limitKey: String?, limitMax: Long?): MoneyResult {
        if (amount <= 0) return MoneyResult.OK
        val args = mutableListOf(player, amount.toString(), reason, ref)
        if (limitKey != null && limitMax != null) args += listOf(limitKey, limitMax.toString())
        return call("eco.api.deposit", args)
    }

    /** Calls `eco.api.withdraw <player> <amount> <reason> <ref>`. */
    override fun withdraw(player: String, amount: Long, reason: String, ref: String): MoneyResult {
        if (amount <= 0) return MoneyResult.OK
        return call("eco.api.withdraw", listOf(player, amount.toString(), reason, ref))
    }

    /** Calls `eco.api.transfer <from> <to> <amount> <reason> <feePermille> <ref>`. */
    override fun transfer(from: String, to: String, amount: Long, reason: String, feePermille: Int, ref: String): MoneyResult {
        if (amount <= 0) return MoneyResult.OK
        return call("eco.api.transfer", listOf(from, to, amount.toString(), reason, feePermille.toString(), ref))
    }

    private fun call(action: String, args: List<String>): MoneyResult {
        val result = runCatching { actions.invoke(ActionInvocation(action, args)) }.getOrNull()
            ?: return MoneyResult.fail(UNAVAILABLE)
        if (result.success) return MoneyResult(true, null, result.lines.firstOrNull()?.trim()?.toLongOrNull())
        val text = result.lines.joinToString(" ").lowercase()
        return MoneyResult.fail(
            when {
                text.startsWith("unknown action") || text.startsWith("unknown or non-bridge action") -> UNAVAILABLE
                "insufficient" in text || "funds" in text -> INSUFFICIENT
                "limit" in text -> LIMIT
                else -> ERROR
            },
        )
    }

    /** Failure codes reported by [ActionKronenGateway]. */
    companion object {
        /** The balance does not cover the amount. */
        const val INSUFFICIENT: String = "insufficient_funds"

        /** A daily limit is exhausted. */
        const val LIMIT: String = "limit"

        /** The economy addon is not installed or failed. */
        const val UNAVAILABLE: String = "economy_unavailable"

        /** Any other failure. */
        const val ERROR: String = "error"
    }
}
