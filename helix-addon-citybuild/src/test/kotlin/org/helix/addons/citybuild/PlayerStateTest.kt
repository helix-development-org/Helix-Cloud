package org.helix.addons.citybuild

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addons.citybuild.model.Home
import org.helix.addons.citybuild.model.JobProgress
import org.helix.addons.citybuild.service.AdminShopService
import org.helix.addons.citybuild.service.CityException
import org.helix.addons.citybuild.service.HomeService
import org.helix.addons.citybuild.service.JobService
import org.helix.addons.citybuild.service.KitService
import org.helix.addons.citybuild.service.RewardService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerStateTest {
    private val clock = TestClock()
    private val kronen = FakeKronen()
    private val ctx = testContext(clock, kronen)
    private val rewards = RewardService(ctx)

    @Test
    fun `kit cooldown, one-time kits and tiers`() {
        val kits = KitService(ctx)
        kits.claim(ALICE, SHARD, "taeglich", 0)
        assertEquals("cooldown", assertFailsWith<CityException> { kits.claim(ALICE, SHARD, "taeglich", 0) }.code)
        kits.claim(ALICE, "CityBuild-2", "taeglich", 0) // per instance
        clock.advanceSeconds(86_399)
        assertFailsWith<CityException> { kits.claim(ALICE, SHARD, "taeglich", 0) }
        clock.advanceSeconds(1)
        kits.claim(ALICE, SHARD, "taeglich", 0)
        kits.claim(ALICE, SHARD, "starter", 0)
        clock.advanceSeconds(10 * 86_400)
        assertEquals("already_claimed", assertFailsWith<CityException> { kits.claim(ALICE, SHARD, "starter", 0) }.code)
        assertEquals("tier", assertFailsWith<CityException> { kits.claim(ALICE, SHARD, "baumeister", 1) }.code)
        kits.claim(ALICE, SHARD, "baumeister", 2)
        val state = kits.state(ALICE, SHARD, 0)
        assertEquals("true", state["taeglich"]!!.let { (it as kotlinx.serialization.json.JsonObject)["available"]!!.jsonPrimitive.content })
        assertEquals("true", (state["starter"] as kotlinx.serialization.json.JsonObject)["claimedOnce"]!!.jsonPrimitive.content)
        kits.claim(ALICE, SHARD, "taeglich", 0)
        kits.claim(ALICE, SHARD, "taeglich", 0, bypass = true)
    }

    @Test
    fun `daily reward streak follows the Berlin calendar day and resets on a gap`() {
        clock.instant = Instant.parse("2026-10-16T21:59:00Z") // 23:59 in Berlin
        assertEquals("1", rewards.claim(ALICE)["day"]!!.jsonPrimitive.content)
        assertEquals(100, kronen.balance(ALICE))
        assertEquals("already_claimed", assertFailsWith<CityException> { rewards.claim(ALICE) }.code)
        clock.instant = Instant.parse("2026-10-16T22:01:00Z") // 00:01 next day in Berlin
        assertEquals("2", rewards.claim(ALICE)["day"]!!.jsonPrimitive.content)
        assertEquals(250, kronen.balance(ALICE))
        clock.instant = Instant.parse("2026-10-19T10:00:00Z") // skipped the 18th (Berlin)
        assertEquals("1", rewards.claim(ALICE)["day"]!!.jsonPrimitive.content)
        // a full week wraps around and day 7 carries a crate key
        repeat(6) { i ->
            clock.advanceSeconds(86_400)
            val body = rewards.claim(ALICE)
            assertEquals((i + 2).toString(), body["day"]!!.jsonPrimitive.content)
            if (i == 5) assertEquals(1, body["keys"]!!.jsonArray.size)
        }
        clock.advanceSeconds(86_400)
        assertEquals("1", rewards.claim(ALICE)["day"]!!.jsonPrimitive.content)
        assertTrue(rewards.state(ALICE)["claimedToday"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `failed reward payout can be retried`() {
        kronen.failAll = "economy_unavailable"
        assertEquals("economy_unavailable", assertFailsWith<CityException> { rewards.claim(ALICE) }.code)
        kronen.failAll = null
        assertEquals("1", rewards.claim(ALICE)["day"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tutorial bonus is paid once`() {
        assertTrue(rewards.completeTutorial(ALICE)["paid"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(rewards.completeTutorial(ALICE)["paid"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(500, kronen.balance(ALICE))
    }

    @Test
    fun `homes respect limit, worlds and names`() {
        val homes = HomeService(ctx)
        homes.setHome(Home(ALICE, SHARD, "a", "plots", 0.0, 64.0, 0.0), 2)
        homes.setHome(Home(ALICE, SHARD, "b", "spawn", 0.0, 64.0, 0.0), 2)
        homes.setHome(Home(ALICE, SHARD, "A", "plots", 5.0, 64.0, 0.0), 2) // moves "a"
        assertEquals("limit", assertFailsWith<CityException> { homes.setHome(Home(ALICE, SHARD, "c", "plots", 0.0, 64.0, 0.0), 2) }.code)
        assertEquals("world_not_allowed", assertFailsWith<CityException> { homes.setHome(Home(ALICE, SHARD, "c", "farm", 0.0, 64.0, 0.0), 3) }.code)
        assertEquals("invalid_name", assertFailsWith<CityException> { homes.setHome(Home(ALICE, SHARD, "a b", "plots", 0.0, 64.0, 0.0), 3) }.code)
        assertEquals(listOf(5.0, 0.0), homes.homes(ALICE, SHARD).map { it.x })
        homes.deleteHome(ALICE, SHARD, "B")
        assertEquals(1, homes.homes(ALICE, SHARD).size)
    }

    @Test
    fun `job slots, switch cooldown, capped payouts and milestones`() {
        val jobs = JobService(ctx, rewards)
        jobs.select(ALICE, 0, "miner")
        jobs.select(ALICE, 1, "farmer")
        assertEquals("already_active", assertFailsWith<CityException> { jobs.select(ALICE, 1, "miner") }.code)
        assertEquals("cooldown", assertFailsWith<CityException> { jobs.select(ALICE, 0, "jaeger") }.code)
        clock.advanceSeconds(24 * 3600)
        jobs.select(ALICE, 0, "jaeger")
        assertEquals(listOf("jaeger", "farmer"), jobs.load(ALICE).active)
        assertEquals("3999", jobs.payout(ALICE, 3999, "jaeger")["paid"]!!.jsonPrimitive.content)
        val capped = jobs.payout(ALICE, 50, "jaeger")
        assertEquals("1", capped["paid"]!!.jsonPrimitive.content)
        assertEquals("true", capped["capped"]!!.jsonPrimitive.content)
        assertEquals("0", jobs.payout(ALICE, 50, "jaeger")["paid"]!!.jsonPrimitive.content)
        assertEquals("level_not_reached", assertFailsWith<CityException> { jobs.milestone(ALICE, "jaeger", 10) }.code)
        jobs.saveProgress(ALICE, mapOf("jaeger" to JobProgress(12, 40), "unknown" to JobProgress(3, 0)))
        assertEquals(setOf("miner", "jaeger", "farmer"), jobs.load(ALICE).jobs.keys)
        assertEquals(12, jobs.load(ALICE).jobs.getValue("jaeger").level)
        assertEquals("jaeger", jobs.load(ALICE).active[0]) // save keeps the slots
        jobs.milestone(ALICE, "jaeger", 10)
        assertEquals(4500, kronen.balance(ALICE))
        assertEquals("already_claimed", assertFailsWith<CityException> { jobs.milestone(ALICE, "jaeger", 10) }.code)
        assertEquals(100, jobs.xpForLevel(1))
    }

    @Test
    fun `merchant prices, units and daily sell limits`() {
        val shop = AdminShopService(ctx)
        kronen.balances[ALICE] = 100
        assertEquals("6", shop.buy(ALICE, SHARD, "stone", 3)["paid"]!!.jsonPrimitive.content)
        assertEquals("not_for_sale", assertFailsWith<CityException> { shop.buy(ALICE, SHARD, "diamond", 1) }.code)
        assertEquals("invalid_amount", assertFailsWith<CityException> { shop.sell(ALICE, SHARD, "wheat", 10) }.code)
        assertEquals("8", shop.sell(ALICE, SHARD, "wheat", 32)["paid"]!!.jsonPrimitive.content)
        assertEquals("1600", shop.sell(ALICE, SHARD, "diamond", 40)["paid"]!!.jsonPrimitive.content)
        assertEquals("limit", assertFailsWith<CityException> { shop.sell(ALICE, SHARD, "diamond", 40) }.code)
        val state = shop.state(ALICE)
        assertEquals("1608", state["kronenSold"]!!.jsonPrimitive.content)
        assertEquals("item_limit", assertFailsWith<CityException> { shop.sell(ALICE, SHARD, "coal", 513) }.code)
        clock.advanceSeconds(86_400)
        kronen.limits.clear() // the economy's daily limit rolls over too
        shop.sell(ALICE, SHARD, "diamond", 40)
    }
}
