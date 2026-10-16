package org.helix.addons.economy

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mutable test clock. */
class TestClock(var now: Instant = Instant.parse("2026-10-16T10:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = BalanceRepository.ZONE

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

/** Behaviour every [BalanceRepository] must show; subclasses supply the backend. */
abstract class BalanceRepositoryContract {
    protected val clock = TestClock()
    private val steve = Holder("00000000-0000-0000-0000-00000000000a", "Steve")
    private val alex = Holder("00000000-0000-0000-0000-00000000000b", "Alex")

    /** Creates a fresh, empty repository with starting balance 1000. */
    abstract fun create(): BalanceRepository

    private val repo by lazy { create() }

    @Test
    fun `unknown accounts read the starting balance and are created on first booking`() {
        assertEquals(1000, repo.balance(steve.uuid))
        assertNull(repo.account(steve.uuid))
        assertEquals(Booking.Done(1040), repo.change(steve, 40, "job"))
        assertEquals(Account(steve.uuid, "Steve", 1040), repo.account(steve.uuid))
    }

    @Test
    fun `withdrawals never overdraw`() {
        assertEquals(Booking.InsufficientFunds, repo.change(steve, -1001, "shop"))
        assertEquals(1000, repo.balance(steve.uuid))
        assertEquals(Booking.Done(0), repo.change(steve, -1000, "shop"))
        assertEquals(Booking.InsufficientFunds, repo.change(steve, -1, "shop"))
    }

    @Test
    fun `transfer moves crowns and withholds the fee`() {
        val outcome = repo.transfer(steve, alex, 200, 50, "auction", "a-1")

        val receipt = assertIs<TransferOutcome.Done>(outcome).receipt
        assertEquals(TransferReceipt(steve.uuid, alex.uuid, 200, 10, 190, 800, 1190), receipt)
        assertEquals(800, repo.balance(steve.uuid))
        assertEquals(1190, repo.balance(alex.uuid))
        val received = repo.history(alex.uuid, 5).first()
        assertEquals(190, received.delta)
        assertEquals(10, received.fee)
        assertEquals(steve.uuid, received.counterparty)
        assertEquals("a-1", received.ref)
        assertEquals(-200, repo.history(steve.uuid, 5).first().delta)
    }

    @Test
    fun `uncovered transfer changes nothing`() {
        assertEquals(TransferOutcome.InsufficientFunds, repo.transfer(steve, alex, 1001, 0, "trade"))
        assertEquals(1000, repo.balance(steve.uuid))
        assertEquals(1000, repo.balance(alex.uuid))
        assertTrue(repo.history(steve.uuid, 5).isEmpty())
    }

    @Test
    fun `daily limit is atomic and resets the next day`() {
        val limit = DailyLimit("citybuild.sell", 3000)
        assertEquals(Booking.Done(3500), repo.change(steve, 2500, "adminshop", limit = limit))
        assertEquals(Booking.LimitReached(500), repo.change(steve, 600, "adminshop", limit = limit))
        assertEquals(3500, repo.balance(steve.uuid), "a rejected booking changes nothing")
        assertEquals(2500, repo.dailyUsed(steve.uuid, limit.key))
        assertEquals(Booking.Done(4000), repo.change(steve, 500, "adminshop", limit = limit))
        assertEquals(Booking.LimitReached(0), repo.change(steve, 1, "adminshop", limit = limit))

        clock.now = clock.now.plusSeconds(24 * 3600)
        assertEquals(0, repo.dailyUsed(steve.uuid, limit.key))
        assertIs<Booking.Done>(repo.change(steve, 3000, "adminshop", limit = limit))
    }

    @Test
    fun `limit is not consumed by a withdrawal that cannot be covered`() {
        val limit = DailyLimit("mineclash.exchange", 500)
        assertEquals(Booking.InsufficientFunds, repo.change(steve, -2000, "exchange", limit = limit))
        assertEquals(0, repo.dailyUsed(steve.uuid, limit.key))
    }

    @Test
    fun `set records the difference and history is newest first`() {
        repo.change(steve, 10, "job")
        assertEquals(50, repo.set(steve, 50, "admin"))
        val history = repo.history(steve.uuid, 10)
        assertEquals(listOf(-960L, 10L), history.map { it.delta })
        assertEquals(50, history.first().balanceAfter)
        assertEquals(1, repo.history(steve.uuid, 1).size)
        assertEquals(clock.now.epochSecond, history.first().at)
    }

    @Test
    fun `import only creates missing accounts`() {
        assertTrue(repo.importBalance(steve, 250))
        assertFalse(repo.importBalance(steve, 999))
        assertEquals(250, repo.balance(steve.uuid))
    }

    @Test
    fun `rename and delete`() {
        repo.change(steve, 1, "job")
        repo.rename(Holder(steve.uuid, "Steve2"))
        assertEquals("Steve2", repo.account(steve.uuid)?.name)
        assertEquals(listOf(steve.uuid), repo.accounts().map { it.uuid })
        assertTrue(repo.delete(steve.uuid))
        assertFalse(repo.delete(steve.uuid))
        assertEquals(1000, repo.balance(steve.uuid))
        assertTrue(repo.history(steve.uuid, 5).isEmpty())
    }

    @Test
    fun `concurrent withdrawals never overdraw`() {
        val pool = Executors.newFixedThreadPool(8)
        val results = (1..40).map { pool.submit<Booking> { repo.change(steve, -100, "shop") } }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        assertEquals(10, results.count { it.get() is Booking.Done })
        assertEquals(0, repo.balance(steve.uuid))
    }

    @Test
    fun `fee rounds down and is clamped`() {
        assertEquals(0, BalanceRepository.fee(19, 50))
        assertEquals(1, BalanceRepository.fee(20, 50))
        assertEquals(100, BalanceRepository.fee(100, 5000))
    }
}
