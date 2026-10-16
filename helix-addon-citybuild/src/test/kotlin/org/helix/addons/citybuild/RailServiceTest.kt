package org.helix.addons.citybuild

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addons.citybuild.config.ConfigDocument
import org.helix.addons.citybuild.config.ConfigSeeds
import org.helix.addons.citybuild.config.RailSeeds
import org.helix.addons.citybuild.config.SeedCollections
import org.helix.addons.citybuild.service.CityException
import org.helix.addons.citybuild.service.RailService
import org.helix.addons.citybuild.store.DocumentRailStore
import org.helix.api.storage.InMemoryAddonStorage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RailServiceTest {
    private val ctx = testContext()
    private val store = DocumentRailStore(InMemoryAddonStorage())
    private val rail = RailService(ctx, store)

    private fun station(id: String) = ctx.config.putDocument(RailSeeds.STATIONS, ConfigDocument(id, 0, JsonObject(emptyMap())))

    @Test
    fun `discovery is per player and instance and idempotent`() {
        station("markt")
        station("nord")
        assertTrue(rail.discover(ALICE, SHARD, "nord"))
        assertFalse(rail.discover(ALICE, SHARD, "nord"))
        assertTrue(rail.discover(ALICE, SHARD, "markt"))
        assertEquals(listOf("markt", "nord"), rail.discovered(ALICE, SHARD))
        assertEquals(emptyList(), rail.discovered(ALICE, "CityBuild-2"))
        assertEquals(emptyList(), rail.discovered(BOB, SHARD))
    }

    @Test
    fun `unknown or malformed stations are rejected`() {
        assertEquals("not_found", assertFailsWith<CityException> { rail.discover(ALICE, SHARD, "nirgendwo") }.code)
        assertEquals("invalid_station", assertFailsWith<CityException> { rail.discover(ALICE, SHARD, "Böse Station") }.code)
    }

    @Test
    fun `concurrent discoveries count once`() {
        station("ost")
        val pool = Executors.newFixedThreadPool(8)
        val added = AtomicInteger()
        repeat(32) { pool.submit { if (rail.discover(ALICE, SHARD, "ost")) added.incrementAndGet() } }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        assertEquals(1, added.get())
    }

    @Test
    fun `gdpr export and delete`() {
        station("west")
        assertNull(rail.dataProvider.export(ALICE))
        rail.discover(ALICE, SHARD, "west")
        val export = kotlinx.serialization.json.Json.parseToJsonElement(rail.dataProvider.export(ALICE)!!).jsonObject
        assertEquals("west", export["railDiscovered"]!!.jsonObject[SHARD]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(rail.dataProvider.delete(ALICE))
        assertFalse(rail.dataProvider.delete(ALICE))
        assertEquals(emptyList(), rail.discovered(ALICE, SHARD))
    }

    @Test
    fun `seeds define the rail collections and settings`() {
        val collections = SeedCollections.all()
        val line = collections.getValue(RailSeeds.LINES).single()
        assertEquals(false, line.body["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(4, line.body["points"]!!.jsonArray.size)
        assertTrue(RailSeeds.STATIONS in collections)
        val keys = ConfigSeeds.settings.map { it.key }
        assertTrue(listOf("rail.viewRadius", "rail.discoverRadius", "rail.updateTicks", "rail.buildBlocksPerTick", "rail.defaultSpeed").all { it in keys })
    }
}
