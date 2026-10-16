package org.helix.addons.citybuild

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.helix.addons.citybuild.config.ConfigDocument
import org.helix.addons.citybuild.config.ConfigSeeds
import org.helix.addons.citybuild.config.ConfigStore
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigUpgradeTest {
    private fun firstVehicle(store: ConfigStore): ConfigDocument =
        store.documents("models").first { it.body["kind"]?.jsonPrimitive?.content == "vehicle" }

    @Test
    fun `an old installation receives new seed fields without losing operator values`() {
        val storage = InMemoryAddonStorage()
        val fresh = ConfigStore(storage)
        val vehicle = firstVehicle(fresh)
        val seedData = vehicle.body["data"]!!.jsonObject
        // simulate a pre-A21 installation: the document lacks the new data fields, the operator changed the price
        val stripped = JsonObject(vehicle.body + mapOf("price" to JsonPrimitive(4242), "data" to buildJsonObject { put("speed", 99) }))
        fresh.putDocument("models", vehicle.copy(body = stripped))
        storage.delete("config.seeds")

        val upgraded = ConfigStore(storage).document("models", vehicle.id)!!
        assertEquals(4242, upgraded.body["price"]!!.jsonPrimitive.content.toInt(), "operator value kept")
        val data = upgraded.body["data"]!!.jsonObject
        assertEquals(99, data["speed"]!!.jsonPrimitive.content.toInt(), "nested operator value kept")
        for (key in seedData.keys - "speed") assertNotNull(data[key], "missing field $key added")
    }

    @Test
    fun `deleted seed documents stay deleted and new seeds still arrive`() {
        val storage = InMemoryAddonStorage()
        val store = ConfigStore(storage)
        val victim = firstVehicle(store)
        store.removeDocument("models", victim.id)
        assertNull(ConfigStore(storage).document("models", victim.id), "operator deletion is respected")

        // a document seeded for the first time (unknown to the stored state) is inserted
        val other = store.documents("kits").first()
        store.removeDocument("kits", other.id)
        val state = storage.read("config.seeds")!!
        storage.write("config.seeds", state.replace("\"kits/${other.id}\"", "\"kits/__gone\""))
        assertNotNull(ConfigStore(storage).document("kits", other.id))
    }

    @Test
    fun `the seed state records the current version`() {
        val storage = InMemoryAddonStorage()
        ConfigStore(storage)
        assert(storage.read("config.seeds")!!.contains("\"version\":${ConfigSeeds.SEED_VERSION}"))
    }
}
