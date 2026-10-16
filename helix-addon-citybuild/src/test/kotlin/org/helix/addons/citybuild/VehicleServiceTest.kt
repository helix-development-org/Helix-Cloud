package org.helix.addons.citybuild

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.helix.addons.citybuild.config.ConfigDocument
import org.helix.addons.citybuild.service.CityException
import org.helix.addons.citybuild.vehicle.DocumentVehicleStore
import org.helix.addons.citybuild.vehicle.VehicleAccess
import org.helix.addons.citybuild.vehicle.VehicleService
import org.helix.addons.citybuild.vehicle.VehicleStates
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehicleServiceTest {
    private val clock = TestClock()
    private val ctx = testContext(clock)
    private val storage = InMemoryAddonStorage()
    private val service = VehicleService(ctx, DocumentVehicleStore(storage))

    init {
        ctx.config.putDocument(
            "models",
            ConfigDocument(
                "kutsche",
                0,
                buildJsonObject {
                    put("kind", "vehicle")
                    put("data", buildJsonObject { put("colors", buildJsonArray { add(JsonPrimitive("red")); add(JsonPrimitive("blue")) }) })
                },
            ),
        )
        ctx.config.putDocument("models", ConfigDocument("stuhl", 0, buildJsonObject { put("kind", "furniture") }))
    }

    @Test
    fun `registers into the garage and lists per instance`() {
        val v = service.register(ALICE, SHARD, "kutsche", 1)
        assertEquals(VehicleStates.GARAGE, v.state)
        assertEquals(1, v.color)
        assertEquals(VehicleAccess.MEMBERS, v.access)
        assertEquals(listOf(v), service.list(ALICE, SHARD))
        assertTrue(service.list(ALICE, "CityBuild-2").isEmpty())
    }

    @Test
    fun `rejects unknown or non-vehicle models and clamps colours`() {
        assertEquals("unknown_model", assertFailsWith<CityException> { service.register(ALICE, SHARD, "nix") }.code)
        assertEquals("invalid_model", assertFailsWith<CityException> { service.register(ALICE, SHARD, "stuhl") }.code)
        assertEquals(1, service.register(ALICE, SHARD, "kutsche", 9).color)
    }

    @Test
    fun `enforces the per-player limit`() {
        ctx.config.setSetting("vehicles.maxPerPlayer", "2")
        service.register(ALICE, SHARD, "kutsche")
        service.register(ALICE, SHARD, "kutsche")
        assertEquals("limit", assertFailsWith<CityException> { service.register(ALICE, SHARD, "kutsche") }.code)
        service.register(BOB, SHARD, "kutsche")
    }

    @Test
    fun `only the owner may update with valid values`() {
        val v = service.register(ALICE, SHARD, "kutsche")
        assertEquals("not_owner", assertFailsWith<CityException> { service.update(BOB, v.id, buildJsonObject { put("locked", true) }) }.code)
        assertEquals("invalid_access", assertFailsWith<CityException> { service.update(ALICE, v.id, buildJsonObject { put("access", "friends") }) }.code)
        assertEquals("invalid_color", assertFailsWith<CityException> { service.update(ALICE, v.id, buildJsonObject { put("color", 5) }) }.code)
        val updated = service.update(ALICE, v.id, buildJsonObject { put("locked", true); put("access", "ALL"); put("color", 1) })
        assertTrue(updated.locked)
        assertEquals(VehicleAccess.ALL, updated.access)
        assertEquals(1, updated.color)
    }

    @Test
    fun `park records positions and recall returns spawned vehicles`() {
        val v = service.register(ALICE, SHARD, "kutsche")
        assertEquals("invalid_position", assertFailsWith<CityException> { service.park(ALICE, v.id, "world", "plots", null, 64.0, 3.0) }.code)
        val spawned = service.park(ALICE, v.id, VehicleStates.WORLD, "plots", 1.0, 64.0, 3.0)
        assertEquals("plots", spawned.world)
        val other = service.register(BOB, "CityBuild-2", "kutsche")
        service.park(BOB, other.id, VehicleStates.WORLD, "plots", 0.0, 0.0, 0.0)
        assertEquals(1, service.recall(SHARD))
        val back = service.list(ALICE, SHARD).single()
        assertEquals(VehicleStates.GARAGE, back.state)
        assertNull(back.world)
        assertEquals(VehicleStates.WORLD, service.list(BOB, "CityBuild-2").single().state)
    }

    @Test
    fun `remove scraps only own vehicles and survives a reload`() {
        val v = service.register(ALICE, SHARD, "kutsche")
        assertEquals("not_owner", assertFailsWith<CityException> { service.remove(BOB, v.id) }.code)
        service.register(ALICE, SHARD, "kutsche")
        service.remove(ALICE, v.id)
        val reloaded = VehicleService(ctx, DocumentVehicleStore(storage))
        assertEquals(1, reloaded.list(ALICE, SHARD).size)
        assertEquals("not_found", assertFailsWith<CityException> { reloaded.remove(ALICE, v.id) }.code)
    }
}
