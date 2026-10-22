package org.helix.addons.economy

import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * A crown account: one per player uuid.
 *
 * @property uuid player uuid (lowercase, dashed).
 * @property name latest known player name.
 * @property balance balance in whole crowns, never negative.
 */
@Serializable
data class Account(val uuid: String, val name: String, val balance: Long)

/**
 * One booking on an account.
 *
 * @property id monotonically increasing booking id.
 * @property uuid account the booking belongs to.
 * @property delta signed change of the balance.
 * @property balanceAfter balance right after the booking.
 * @property reason short machine reason such as `job`, `shop` or `pay`.
 * @property ref optional caller reference (auction id, plot id, …).
 * @property counterparty uuid of the other account for transfers.
 * @property fee crowns withheld as fee (only on the receiving side of a transfer).
 * @property at epoch seconds of the booking.
 */
@Serializable
data class LedgerEntry(
    val id: Long,
    val uuid: String,
    val delta: Long,
    val balanceAfter: Long,
    val reason: String,
    val ref: String? = null,
    val counterparty: String? = null,
    val fee: Long = 0,
    val at: Long,
)

/**
 * A party of a booking: account uuid plus the name to remember for it.
 *
 * @property uuid account uuid.
 * @property name latest known player name (stored with the account).
 */
data class Holder(val uuid: String, val name: String)

/**
 * A network-wide daily cap checked atomically together with a booking. The
 * absolute booked amount is added to the counter `(uuid, day, key)`; the day
 * is the calendar day in Europe/Berlin.
 *
 * @property key counter key, for example `citybuild.sell`.
 * @property max maximum total per player and day.
 */
data class DailyLimit(val key: String, val max: Long)

/** Result of a single-account booking. */
sealed interface Booking {
    /**
     * Booking applied.
     *
     * @property balance new balance.
     */
    data class Done(val balance: Long) : Booking

    /** The balance does not cover the withdrawal; nothing changed. */
    data object InsufficientFunds : Booking

    /**
     * The daily limit would be exceeded; nothing changed.
     *
     * @property remaining crowns still allowed today (≥ 0).
     */
    data class LimitReached(val remaining: Long) : Booking
}

/**
 * Receipt of an applied transfer.
 *
 * @property from paying account uuid.
 * @property to receiving account uuid.
 * @property amount crowns taken from the payer.
 * @property fee crowns withheld (money sink).
 * @property received crowns credited to the receiver (`amount - fee`).
 * @property fromBalance payer balance afterwards.
 * @property toBalance receiver balance afterwards.
 */
@Serializable
data class TransferReceipt(
    val from: String,
    val to: String,
    val amount: Long,
    val fee: Long,
    val received: Long,
    val fromBalance: Long,
    val toBalance: Long,
)

/** Result of a transfer. */
sealed interface TransferOutcome {
    /**
     * Transfer applied.
     *
     * @property receipt booking details.
     */
    data class Done(val receipt: TransferReceipt) : TransferOutcome

    /** The payer cannot cover the amount; nothing changed. */
    data object InsufficientFunds : TransferOutcome
}

/**
 * Persistence of crown accounts, the booking ledger and the daily limit
 * counters. Every mutating call is atomic: either all of its effects
 * (balances, counter, ledger rows) are stored or none.
 *
 * Accounts are created lazily on the first booking with [startingBalance];
 * reading an unknown account yields [startingBalance].
 */
interface BalanceRepository : AutoCloseable {
    /** Balance an account starts with on its first booking. */
    val startingBalance: Long

    /**
     * Reads a balance.
     *
     * @param uuid account uuid.
     * @return the balance, [startingBalance] for unknown accounts.
     */
    fun balance(uuid: String): Long

    /**
     * Reads a stored account.
     *
     * @param uuid account uuid.
     * @return the account or `null` when it never booked.
     */
    fun account(uuid: String): Account?

    /**
     * All stored accounts.
     *
     * @return accounts, highest balance first.
     */
    fun accounts(): List<Account>

    /**
     * Applies a signed change to one account.
     *
     * @param holder the account.
     * @param delta crowns to add (negative = withdraw).
     * @param reason machine reason stored in the ledger.
     * @param ref optional caller reference.
     * @param limit optional daily cap counting `|delta|`.
     * @return the outcome.
     */
    fun change(holder: Holder, delta: Long, reason: String, ref: String? = null, limit: DailyLimit? = null): Booking

    /**
     * Overwrites a balance (admin), recorded in the ledger as the difference.
     *
     * @param holder the account.
     * @param amount new balance, not negative.
     * @param reason machine reason.
     * @return the new balance.
     */
    fun set(holder: Holder, amount: Long, reason: String): Long

    /**
     * Moves crowns between two accounts in one transaction. The payer is
     * charged [amount]; the receiver gets `amount - fee` with
     * `fee = amount * feePermille / 1000` (rounded down).
     *
     * @param from paying account.
     * @param to receiving account (must differ from [from]).
     * @param amount crowns, positive.
     * @param feePermille fee in per mille, 0..1000.
     * @param reason machine reason.
     * @param ref optional caller reference.
     * @return the outcome.
     */
    fun transfer(from: Holder, to: Holder, amount: Long, feePermille: Int, reason: String, ref: String? = null): TransferOutcome

    /**
     * Latest bookings of an account.
     *
     * @param uuid account uuid.
     * @param limit maximum number of entries.
     * @return entries, newest first.
     */
    fun history(uuid: String, limit: Int): List<LedgerEntry>

    /**
     * Today's value of a daily limit counter.
     *
     * @param uuid account uuid.
     * @param key counter key.
     * @return crowns counted today.
     */
    fun dailyUsed(uuid: String, key: String): Long

    /**
     * Imports a balance for an account that has no record yet (legacy migration).
     *
     * @param holder the account.
     * @param amount balance to store.
     * @return whether the account was created.
     */
    fun importBalance(holder: Holder, amount: Long): Boolean

    /**
     * Updates the stored name of an existing account (rename on join).
     *
     * @param holder uuid plus current name.
     */
    fun rename(holder: Holder)

    /**
     * Deletes an account with its ledger and counters (data deletion request).
     *
     * @param uuid account uuid.
     * @return whether an account existed.
     */
    fun delete(uuid: String): Boolean

    /**
     * Counts or deletes every account with its ledger and daily counters (network release reset).
     *
     * @param dryRun `true` only counts.
     * @return record counts per data set (`accounts`, `ledger`, `daily_counters`), counted before deletion.
     */
    fun wipeAll(dryRun: Boolean): Map<String, Int>

    /** Releases resources such as connection pools. */
    override fun close() {
    }

    /** Shared rules of all repositories. */
    companion object {
        /** Time zone of the daily limit day. */
        val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

        /**
         * The fee of a transfer.
         *
         * @param amount transferred crowns.
         * @param feePermille fee in per mille.
         * @return withheld crowns, rounded down.
         */
        fun fee(amount: Long, feePermille: Int): Long = amount * feePermille.coerceIn(0, 1000) / 1000

        /**
         * The limit day for a clock.
         *
         * @param clock time source.
         * @return today's date in [ZONE].
         */
        fun today(clock: Clock): LocalDate = LocalDate.now(clock.withZone(ZONE))
    }
}
