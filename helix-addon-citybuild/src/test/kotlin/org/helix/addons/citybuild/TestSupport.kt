package org.helix.addons.citybuild

import org.helix.addons.citybuild.config.ConfigStore
import org.helix.addons.citybuild.economy.KronenGateway
import org.helix.addons.citybuild.economy.MoneyResult
import org.helix.addons.citybuild.service.CityContext
import org.helix.addons.citybuild.store.DocumentCityRepository
import org.helix.api.storage.InMemoryAddonStorage
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Mutable clock for tests. */
class TestClock(var instant: Instant = Instant.parse("2026-10-16T10:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant, zone)
    override fun instant(): Instant = instant
    fun advanceSeconds(seconds: Long) {
        instant = instant.plusSeconds(seconds)
    }
}

/** In-memory economy: balances per uuid, fee leaves the economy, optional slow transfers. */
class FakeKronen(var transferDelayMillis: Long = 0) : KronenGateway {
    val balances = mutableMapOf<String, Long>()
    val bookings = mutableListOf<String>()
    var failAll: String? = null
    val limits = mutableMapOf<String, Long>()

    @Synchronized fun balance(uuid: String) = balances[uuid] ?: 0

    override fun deposit(player: String, amount: Long, reason: String, ref: String, limitKey: String?, limitMax: Long?): MoneyResult = synchronized(this) {
        failAll?.let { return MoneyResult.fail(it) }
        if (limitKey != null && limitMax != null) {
            val used = limits["$player:$limitKey"] ?: 0
            if (used + amount > limitMax) return MoneyResult.fail("limit")
            limits["$player:$limitKey"] = used + amount
        }
        balances[player] = balance(player) + amount
        bookings += "deposit $player $amount $reason"
        MoneyResult(true, null, balances[player])
    }

    override fun withdraw(player: String, amount: Long, reason: String, ref: String): MoneyResult = synchronized(this) {
        failAll?.let { return MoneyResult.fail(it) }
        if (balance(player) < amount) return MoneyResult.fail("insufficient_funds")
        balances[player] = balance(player) - amount
        bookings += "withdraw $player $amount $reason"
        MoneyResult(true, null, balances[player])
    }

    override fun transfer(from: String, to: String, amount: Long, reason: String, feePermille: Int, ref: String): MoneyResult {
        if (transferDelayMillis > 0) Thread.sleep(transferDelayMillis)
        synchronized(this) {
            failAll?.let { return MoneyResult.fail(it) }
            if (balance(from) < amount) return MoneyResult.fail("insufficient_funds")
            val fee = amount * feePermille / 1000
            balances[from] = balance(from) - amount
            balances[to] = balance(to) + amount - fee
            bookings += "transfer $from $to $amount fee $fee $reason"
            return MoneyResult.OK
        }
    }
}

/** Fresh services context on the document repository. */
fun testContext(clock: TestClock = TestClock(), kronen: FakeKronen = FakeKronen()): CityContext {
    val storage = InMemoryAddonStorage()
    return CityContext(DocumentCityRepository(storage), ConfigStore(storage), kronen, clock)
}

const val ALICE = "11111111-1111-1111-1111-111111111111"
const val BOB = "22222222-2222-2222-2222-222222222222"
const val CAROL = "33333333-3333-3333-3333-333333333333"
const val SHARD = "CityBuild-1"
