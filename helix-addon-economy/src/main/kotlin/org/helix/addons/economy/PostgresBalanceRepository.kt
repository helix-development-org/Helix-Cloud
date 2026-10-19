package org.helix.addons.economy

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.helix.api.addon.StorageConnection
import java.sql.Connection
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import javax.sql.DataSource

/**
 * [BalanceRepository] on the node's PostgreSQL database, schema `economy`:
 *
 * - `accounts(uuid PK, name, balance CHECK >= 0, updated_at)`
 * - `ledger(id BIGSERIAL, uuid, delta, balance_after, reason, ref, counterparty, fee, created_at)`
 * - `daily_counters(uuid, day, key, amount, PK(uuid, day, key))`
 *
 * Every mutation runs in one transaction and locks the touched account rows
 * (`SELECT … FOR UPDATE`, transfers lock both in uuid order to avoid
 * deadlocks), so concurrent bookings from several services serialise
 * correctly. The driver and HikariCP come from the node classloader.
 *
 * @property dataSource connection source (a Hikari pool in production).
 * @property startingBalance balance of new accounts.
 * @property clock time source for ledger timestamps and the limit day.
 * @property owned whether [close] shuts the data source down.
 */
class PostgresBalanceRepository(
    private val dataSource: DataSource,
    override val startingBalance: Long,
    private val clock: Clock = Clock.system(BalanceRepository.ZONE),
    private val owned: Boolean = false,
) : BalanceRepository {
    /**
     * Opens a pool for the node's storage connection.
     *
     * @param connection the node's storage connection settings.
     * @param startingBalance balance of new accounts.
     */
    constructor(connection: StorageConnection, startingBalance: Long) : this(pool(connection), startingBalance, owned = true)

    init {
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE SCHEMA IF NOT EXISTS economy")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS economy.accounts (
                        uuid TEXT PRIMARY KEY,
                        name TEXT NOT NULL DEFAULT '',
                        balance BIGINT NOT NULL CHECK (balance >= 0),
                        updated_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent(),
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS accounts_balance_idx ON economy.accounts (balance DESC)")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS economy.ledger (
                        id BIGSERIAL PRIMARY KEY,
                        uuid TEXT NOT NULL,
                        delta BIGINT NOT NULL,
                        balance_after BIGINT NOT NULL,
                        reason TEXT NOT NULL,
                        ref TEXT,
                        counterparty TEXT,
                        fee BIGINT NOT NULL DEFAULT 0,
                        created_at TIMESTAMPTZ NOT NULL
                    )
                    """.trimIndent(),
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS ledger_uuid_idx ON economy.ledger (uuid, id DESC)")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS economy.daily_counters (
                        uuid TEXT NOT NULL,
                        day DATE NOT NULL,
                        key TEXT NOT NULL,
                        amount BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (uuid, day, key)
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    override fun balance(uuid: String): Long = account(uuid)?.balance ?: startingBalance

    override fun account(uuid: String): Account? =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT uuid, name, balance FROM economy.accounts WHERE uuid = ?").use { st ->
                st.setString(1, uuid)
                st.executeQuery().use { rs -> if (rs.next()) Account(rs.getString(1), rs.getString(2), rs.getLong(3)) else null }
            }
        }

    override fun accounts(): List<Account> =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT uuid, name, balance FROM economy.accounts ORDER BY balance DESC").use { st ->
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(Account(rs.getString(1), rs.getString(2), rs.getLong(3))) } }
            }
        }

    override fun change(holder: Holder, delta: Long, reason: String, ref: String?, limit: DailyLimit?): Booking =
        transaction { c ->
            val current = lockAccount(c, holder)
            val updated = current + delta
            if (updated < 0) {
                return@transaction Booking.InsufficientFunds
            }
            if (limit != null) {
                val used = lockCounter(c, holder.uuid, limit.key)
                val amount = kotlin.math.abs(delta)
                if (used + amount > limit.max) {
                    return@transaction Booking.LimitReached((limit.max - used).coerceAtLeast(0))
                }
                c.prepareStatement("UPDATE economy.daily_counters SET amount = ? WHERE uuid = ? AND day = ? AND key = ?").use { st ->
                    st.setLong(1, used + amount)
                    st.setString(2, holder.uuid)
                    st.setDate(3, today())
                    st.setString(4, limit.key)
                    st.executeUpdate()
                }
            }
            writeBalance(c, holder, updated)
            book(c, holder.uuid, delta, updated, reason, ref, null, 0)
            Booking.Done(updated)
        }

    override fun set(holder: Holder, amount: Long, reason: String): Long {
        require(amount >= 0) { "balance must not be negative" }
        return transaction { c ->
            val before = lockAccount(c, holder)
            writeBalance(c, holder, amount)
            book(c, holder.uuid, amount - before, amount, reason, null, null, 0)
            amount
        }
    }

    override fun transfer(from: Holder, to: Holder, amount: Long, feePermille: Int, reason: String, ref: String?): TransferOutcome {
        require(amount > 0) { "amount must be positive" }
        require(from.uuid != to.uuid) { "cannot transfer to the same account" }
        return transaction { c ->
            val balances = listOf(from, to).sortedBy { it.uuid }.associate { it.uuid to lockAccount(c, it) }
            val fromBalance = balances.getValue(from.uuid) - amount
            if (fromBalance < 0) {
                return@transaction TransferOutcome.InsufficientFunds
            }
            val fee = BalanceRepository.fee(amount, feePermille)
            val received = amount - fee
            val toBalance = balances.getValue(to.uuid) + received
            writeBalance(c, from, fromBalance)
            writeBalance(c, to, toBalance)
            book(c, from.uuid, -amount, fromBalance, reason, ref, to.uuid, 0)
            book(c, to.uuid, received, toBalance, reason, ref, from.uuid, fee)
            TransferOutcome.Done(TransferReceipt(from.uuid, to.uuid, amount, fee, received, fromBalance, toBalance))
        }
    }

    override fun history(uuid: String, limit: Int): List<LedgerEntry> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT id, uuid, delta, balance_after, reason, ref, counterparty, fee, created_at FROM economy.ledger " +
                    "WHERE uuid = ? ORDER BY id DESC LIMIT ?",
            ).use { st ->
                st.setString(1, uuid)
                st.setInt(2, limit.coerceAtLeast(0))
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                LedgerEntry(
                                    id = rs.getLong(1),
                                    uuid = rs.getString(2),
                                    delta = rs.getLong(3),
                                    balanceAfter = rs.getLong(4),
                                    reason = rs.getString(5),
                                    ref = rs.getString(6),
                                    counterparty = rs.getString(7),
                                    fee = rs.getLong(8),
                                    at = rs.getTimestamp(9).toInstant().epochSecond,
                                ),
                            )
                        }
                    }
                }
            }
        }

    override fun dailyUsed(uuid: String, key: String): Long =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT amount FROM economy.daily_counters WHERE uuid = ? AND day = ? AND key = ?").use { st ->
                st.setString(1, uuid)
                st.setDate(2, today())
                st.setString(3, key)
                st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }

    override fun importBalance(holder: Holder, amount: Long): Boolean =
        transaction { c ->
            val inserted = c.prepareStatement(
                "INSERT INTO economy.accounts (uuid, name, balance, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT (uuid) DO NOTHING",
            ).use { st ->
                st.setString(1, holder.uuid)
                st.setString(2, holder.name)
                st.setLong(3, amount.coerceAtLeast(0))
                st.setTimestamp(4, now())
                st.executeUpdate() == 1
            }
            if (inserted) {
                book(c, holder.uuid, amount.coerceAtLeast(0), amount.coerceAtLeast(0), "migration", null, null, 0)
            }
            inserted
        }

    override fun rename(holder: Holder) {
        if (holder.name.isBlank()) {
            return
        }
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE economy.accounts SET name = ? WHERE uuid = ? AND name <> ?").use { st ->
                st.setString(1, holder.name)
                st.setString(2, holder.uuid)
                st.setString(3, holder.name)
                st.executeUpdate()
            }
        }
    }

    override fun delete(uuid: String): Boolean =
        transaction { c ->
            listOf("economy.ledger", "economy.daily_counters").forEach { table ->
                c.prepareStatement("DELETE FROM $table WHERE uuid = ?").use { st ->
                    st.setString(1, uuid)
                    st.executeUpdate()
                }
            }
            c.prepareStatement("DELETE FROM economy.accounts WHERE uuid = ?").use { st ->
                st.setString(1, uuid)
                st.executeUpdate() == 1
            }
        }

    override fun wipeAll(dryRun: Boolean): Map<String, Int> =
        transaction { c ->
            val counts = linkedMapOf<String, Int>()
            listOf("accounts", "ledger", "daily_counters").forEach { table ->
                counts[table] = c.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM economy.$table").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            }
            if (!dryRun) {
                c.createStatement().use { st -> st.execute("TRUNCATE economy.ledger, economy.daily_counters, economy.accounts") }
            }
            counts
        }

    override fun close() {
        if (owned) {
            (dataSource as? AutoCloseable)?.close()
        }
    }

    /** Ensures the account row exists (with the starting balance) and locks it; returns its balance. */
    private fun lockAccount(c: Connection, holder: Holder): Long {
        c.prepareStatement(
            "INSERT INTO economy.accounts (uuid, name, balance, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT (uuid) DO NOTHING",
        ).use { st ->
            st.setString(1, holder.uuid)
            st.setString(2, holder.name)
            st.setLong(3, startingBalance)
            st.setTimestamp(4, now())
            st.executeUpdate()
        }
        return c.prepareStatement("SELECT balance FROM economy.accounts WHERE uuid = ? FOR UPDATE").use { st ->
            st.setString(1, holder.uuid)
            st.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    /** Ensures today's counter row exists and locks it; returns its amount. */
    private fun lockCounter(c: Connection, uuid: String, key: String): Long {
        c.prepareStatement(
            "INSERT INTO economy.daily_counters (uuid, day, key, amount) VALUES (?, ?, ?, 0) ON CONFLICT DO NOTHING",
        ).use { st ->
            st.setString(1, uuid)
            st.setDate(2, today())
            st.setString(3, key)
            st.executeUpdate()
        }
        return c.prepareStatement("SELECT amount FROM economy.daily_counters WHERE uuid = ? AND day = ? AND key = ? FOR UPDATE").use { st ->
            st.setString(1, uuid)
            st.setDate(2, today())
            st.setString(3, key)
            st.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    private fun writeBalance(c: Connection, holder: Holder, balance: Long) {
        c.prepareStatement(
            "UPDATE economy.accounts SET balance = ?, name = CASE WHEN ? = '' THEN name ELSE ? END, updated_at = ? WHERE uuid = ?",
        ).use { st ->
            st.setLong(1, balance)
            st.setString(2, holder.name)
            st.setString(3, holder.name)
            st.setTimestamp(4, now())
            st.setString(5, holder.uuid)
            st.executeUpdate()
        }
    }

    private fun book(c: Connection, uuid: String, delta: Long, after: Long, reason: String, ref: String?, counterparty: String?, fee: Long) {
        c.prepareStatement(
            "INSERT INTO economy.ledger (uuid, delta, balance_after, reason, ref, counterparty, fee, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.setString(1, uuid)
            st.setLong(2, delta)
            st.setLong(3, after)
            st.setString(4, reason)
            st.setString(5, ref)
            st.setString(6, counterparty)
            st.setLong(7, fee)
            st.setTimestamp(8, now())
            st.executeUpdate()
        }
    }

    /** Runs [block] in one transaction; commits on return, rolls back on exception or a non-applied outcome. */
    private fun <T> transaction(block: (Connection) -> T): T =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val result = block(c)
                if (result is Booking.InsufficientFunds || result is Booking.LimitReached || result is TransferOutcome.InsufficientFunds) {
                    c.rollback()
                } else {
                    c.commit()
                }
                result
            } catch (failure: Exception) {
                c.rollback()
                throw failure
            } finally {
                c.autoCommit = true
            }
        }

    private fun now(): Timestamp = Timestamp.from(clock.instant())

    private fun today(): Date = Date.valueOf(BalanceRepository.today(clock))

    private companion object {
        /** Creates the connection pool for [connection]. */
        fun pool(connection: StorageConnection): HikariDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = connection.url
                    username = connection.user
                    password = connection.password
                    maximumPoolSize = connection.poolSize.coerceIn(1, 8)
                    poolName = "helix-economy"
                },
            )
    }
}
