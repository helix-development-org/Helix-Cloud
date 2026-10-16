package org.helix.addons.citybuild

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.addons.citybuild.config.ConfigSeeds
import org.helix.addons.citybuild.config.ConfigStore
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigSeedsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `seeds carry the concept defaults`() {
        val store = ConfigStore(InMemoryAddonStorage())
        assertEquals("0,5000,12000,20000,30000,45000,60000,80000,100000", store.setting("plots.prices"))
        assertEquals("2500", store.setting("plots.mergePrice"))
        assertEquals("3000", store.setting("adminshop.dailySellCap"))
        assertEquals("4000", store.setting("jobs.dailyCap"))
        assertEquals("50", store.setting("fees.auctionSalePermille"))
        assertEquals("SUNDAY", store.setting("farm.resetDay"))
        assertEquals("CITYBUILD", store.document("servers", ConfigSeeds.TASK)!!.body["mode"]!!.jsonPrimitive.content)
        assertEquals(3, store.document("servers", ConfigSeeds.TASK)!!.body["instances"]!!.jsonArray.size)
        assertEquals("40", store.document("adminshop", "diamond")!!.body["sell"]!!.jsonPrimitive.content)
        assertEquals("16", store.document("adminshop", "wheat")!!.body["unit"]!!.jsonPrimitive.content)
        assertEquals(8.0, store.document("jobs", "miner")!!.body["actions"]!!.jsonObject["break:DIAMOND_ORE"]!!.jsonPrimitive.content.toDouble())
        assertEquals("8", store.document("limits", "tier3")!!.body["plots"]!!.jsonPrimitive.content)
        assertEquals("1000", store.document("rewards", "day7")!!.body["kronen"]!!.jsonPrimitive.content)
        assertEquals(10, store.documents("villagers").size)
        assertEquals(6, store.documents("enchants").size)
        assertEquals(9, store.documents("abilities").size)
        assertEquals(4, store.documents("kits").size)
        assertNotNull(store.document("crates", "standard"))
        assertTrue(store.documents("adminshop").size > 150)
        // ware ids are unique
        assertEquals(store.documents("adminshop").size, store.documents("adminshop").map { it.id }.toSet().size)
    }

    @Test
    fun `every citybuild action is bridge invocable and the bundle is served`() {
        val context = RecordingAddonContext(createTempDirectory("citybuild"))
        CityBuildAddon(gatewayFactory = { FakeKronen() }).onEnable(context)
        val actions = context.handlers.filterKeys { it.startsWith("citybuild.") }
        assertTrue(actions.size > 60, "expected many actions, got ${actions.size}")
        actions.values.forEach { (descriptor, _) -> assertTrue(descriptor.bridgeInvocable, "${descriptor.name} must be bridge invocable") }
        actions.values.forEach { (descriptor, _) ->
            assertEquals(setOf(ConfigSeeds.TASK), descriptor.bridgeTasks, "${descriptor.name} must be limited to CityBuild servers")
        }
        val bundle = context.run("citybuild.config.bundle")
        assertTrue(bundle.success)
        val parsed = json.parseToJsonElement(bundle.lines.single()).jsonObject
        assertTrue("adminshop" in parsed.getValue("collections").jsonObject)
        assertTrue(context.run("citybuild.config.set", "plots.mergePrice", "3000").success)
        assertFalse(context.run("citybuild.config.set", "plots.mergePrice", "x").success)
        val usage = context.run("citybuild.home.set", ALICE)
        assertFalse(usage.success)
        assertEquals("usage", usage.lines.first())
        assertTrue(context.profileInfoProviders.isNotEmpty())
        assertTrue(context.playerDataProviders.isNotEmpty())
    }
}
