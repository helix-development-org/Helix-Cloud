package org.helix.addons.economy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage
import java.time.Clock

/**
 * [BalanceRepository] on the addon's document storage, used when the node
 * runs without PostgreSQL (json mode). All accounts live in one document,
 * each account's ledger (newest [LEDGER_KEEP] bookings) and daily counters
 * in their own documents. Atomicity comes from a single in-process lock,
 * which is enough because exactly one node owns the storage.
 *
 * @property storage addon-scoped document store.
 * @property startingBalance balance of new accounts.
 * @property clock time source for ledger timestamps and the limit day.
 */
class DocumentBalanceRepository(
    private val storage: AddonStorage,
    override val startingBalance: Long,
    private val clock: Clock = Clock.system(BalanceRepository.ZONE),
) : BalanceRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val accounts = linkedMapOf<String, StoredAccount>()
    private var sequence = 0L

    init {
        storage.read(ACCOUNTS)?.let { raw ->
            json.decodeFromString<Map<String, StoredAccount>>(raw).forEach { (uuid, account) -> accounts[uuid] = account }
        }
        sequence = storage.read(SEQUENCE)?.toLongOrNull() ?: 0L
    }

    @Synchronized
    override fun balance(uuid: String): Long = accounts[uuid]?.balance ?: startingBalance

    @Synchronized
    override fun account(uuid: String): Account? = accounts[uuid]?.let { Account(uuid, it.name, it.balance) }

    @Synchronized
    override fun accounts(): List<Account> =
        accounts.map { (uuid, account) -> Account(uuid, account.name, account.balance) }.sortedByDescending { it.balance }

    @Synchronized
    override fun change(holder: Holder, delta: Long, reason: String, ref: String?, limit: DailyLimit?): Booking {
        val updated = balance(holder.uuid) + delta
        if (updated < 0) {
            return Booking.InsufficientFunds
        }
        val counters = limit?.let { readCounters(holder.uuid) }
        if (limit != null && counters != null) {
            val used = counters.used(limit.key, today())
            if (used + kotlin.math.abs(delta) > limit.max) {
                return Booking.LimitReached((limit.max - used).coerceAtLeast(0))
            }
            counters.add(limit.key, today(), kotlin.math.abs(delta))
            storage.write(DAILY_PREFIX + holder.uuid, json.encodeToString(counters))
        }
        store(holder, updated)
        book(holder.uuid, delta, updated, reason, ref, null, 0)
        persistAccounts()
        return Booking.Done(updated)
    }

    @Synchronized
    override fun set(holder: Holder, amount: Long, reason: String): Long {
        require(amount >= 0) { "balance must not be negative" }
        val before = balance(holder.uuid)
        store(holder, amount)
        book(holder.uuid, amount - before, amount, reason, null, null, 0)
        persistAccounts()
        return amount
    }

    @Synchronized
    override fun transfer(from: Holder, to: Holder, amount: Long, feePermille: Int, reason: String, ref: String?): TransferOutcome {
        require(amount > 0) { "amount must be positive" }
        require(from.uuid != to.uuid) { "cannot transfer to the same account" }
        val fromBalance = balance(from.uuid) - amount
        if (fromBalance < 0) {
            return TransferOutcome.InsufficientFunds
        }
        val fee = BalanceRepository.fee(amount, feePermille)
        val received = amount - fee
        val toBalance = balance(to.uuid) + received
        store(from, fromBalance)
        store(to, toBalance)
        book(from.uuid, -amount, fromBalance, reason, ref, to.uuid, 0)
        book(to.uuid, received, toBalance, reason, ref, from.uuid, fee)
        persistAccounts()
        return TransferOutcome.Done(TransferReceipt(from.uuid, to.uuid, amount, fee, received, fromBalance, toBalance))
    }

    @Synchronized
    override fun history(uuid: String, limit: Int): List<LedgerEntry> = readLedger(uuid).take(limit.coerceAtLeast(0))

    @Synchronized
    override fun dailyUsed(uuid: String, key: String): Long = readCounters(uuid).used(key, today())

    @Synchronized
    override fun importBalance(holder: Holder, amount: Long): Boolean {
        if (accounts.containsKey(holder.uuid)) {
            return false
        }
        store(holder, amount.coerceAtLeast(0))
        book(holder.uuid, amount.coerceAtLeast(0), amount.coerceAtLeast(0), "migration", null, null, 0)
        persistAccounts()
        return true
    }

    @Synchronized
    override fun rename(holder: Holder) {
        val account = accounts[holder.uuid] ?: return
        if (holder.name.isNotBlank() && account.name != holder.name) {
            accounts[holder.uuid] = account.copy(name = holder.name)
            persistAccounts()
        }
    }

    @Synchronized
    override fun delete(uuid: String): Boolean {
        val existed = accounts.remove(uuid) != null
        storage.delete(LEDGER_PREFIX + uuid)
        storage.delete(DAILY_PREFIX + uuid)
        if (existed) {
            persistAccounts()
        }
        return existed
    }

    @Synchronized
    override fun wipeAll(dryRun: Boolean): Map<String, Int> {
        val ledgers = storage.keys().filter { it.startsWith(LEDGER_PREFIX) && it != SEQUENCE }
        val dailies = storage.keys().filter { it.startsWith(DAILY_PREFIX) }
        val counts = linkedMapOf("accounts" to accounts.size, "ledger" to ledgers.size, "daily_counters" to dailies.size)
        if (!dryRun) {
            ledgers.forEach(storage::delete)
            dailies.forEach(storage::delete)
            accounts.clear()
            persistAccounts()
        }
        return counts
    }

    private fun today(): String = BalanceRepository.today(clock).toString()

    private fun store(holder: Holder, balance: Long) {
        val name = holder.name.ifBlank { accounts[holder.uuid]?.name.orEmpty() }
        accounts[holder.uuid] = StoredAccount(name, balance)
    }

    private fun book(uuid: String, delta: Long, after: Long, reason: String, ref: String?, counterparty: String?, fee: Long) {
        sequence += 1
        storage.write(SEQUENCE, sequence.toString())
        val entry = LedgerEntry(sequence, uuid, delta, after, reason, ref, counterparty, fee, clock.instant().epochSecond)
        val ledger = listOf(entry) + readLedger(uuid).take(LEDGER_KEEP - 1)
        storage.write(LEDGER_PREFIX + uuid, json.encodeToString(ledger))
    }

    private fun readLedger(uuid: String): List<LedgerEntry> =
        storage.read(LEDGER_PREFIX + uuid)?.let { json.decodeFromString<List<LedgerEntry>>(it) }.orEmpty()

    private fun readCounters(uuid: String): Counters =
        storage.read(DAILY_PREFIX + uuid)?.let { json.decodeFromString<Counters>(it) } ?: Counters()

    private fun persistAccounts() {
        storage.write(ACCOUNTS, json.encodeToString(accounts.toMap()))
    }

    /** Stored part of an account (the uuid is the map key). */
    @Serializable
    private data class StoredAccount(val name: String, val balance: Long)

    /** Daily counters of one account: key to (day, amount); stale days count as zero. */
    @Serializable
    private data class Counters(val values: MutableMap<String, DayAmount> = linkedMapOf()) {
        /** Today's amount of [key], zero when the stored value is from another day. */
        fun used(key: String, day: String): Long = values[key]?.takeIf { it.day == day }?.amount ?: 0

        /** Adds [amount] to today's value of [key]. */
        fun add(key: String, day: String, amount: Long) {
            values[key] = DayAmount(day, used(key, day) + amount)
        }
    }

    /** One counter value. */
    @Serializable
    private data class DayAmount(val day: String, val amount: Long)

    private companion object {
        const val ACCOUNTS = "accounts"
        const val SEQUENCE = "ledger.sequence"
        const val LEDGER_PREFIX = "ledger."
        const val DAILY_PREFIX = "daily."

        /** Bookings kept per account in json mode. */
        const val LEDGER_KEEP = 200
    }
}
