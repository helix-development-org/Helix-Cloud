package org.helix.addons.mineclash

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addons.mineclash.config.ConfigDocument
import org.helix.addons.mineclash.config.ConfigSeeds
import org.helix.addons.mineclash.config.ConfigStore
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigStoreTest {
    @Test
    fun `seeds defaults once and keeps operator changes`() {
        val storage = InMemoryAddonStorage()
        val store = ConfigStore(storage)
        assertEquals("5000", store.setting("war.pointsWin"))
        assertEquals(13, store.documents("cards").size)
        assertEquals("zombie", store.documents("cards").first().id)
        assertEquals(listOf("buildings", "cards", "chests", "perks", "servers"), store.collectionNames())

        store.setSetting("war.pointsWin", "7000")
        val reloaded = ConfigStore(storage)
        assertEquals("7000", reloaded.setting("war.pointsWin"))
        assertEquals(0, reloaded.seed(force = false))
        assertEquals("5000", reloaded.resetSetting("war.pointsWin"))
        assertNull(reloaded.resetSetting("unknown.key"))
    }

    @Test
    fun `validates values against seed types and bumps the version`() {
        val store = ConfigStore(InMemoryAddonStorage())
        val before = store.version()
        assertEquals("true", store.setSetting("towers.decor", "TRUE"))
        assertFailsWith<IllegalArgumentException> { store.setSetting("war.pointsWin", "abc") }
        assertFailsWith<IllegalArgumentException> { store.setSetting("deck.slotCosts", "1,x") }
        assertEquals("free text", store.setSetting("custom.key", " free text "))
        assertEquals(before + 2, store.version())
    }

    @Test
    fun `documents are ordered and rendered into the bundle`() {
        val store = ConfigStore(InMemoryAddonStorage())
        store.putDocument("cards", ConfigDocument("test_card", 99, buildJsonObject { put("cost", JsonPrimitive(3)) }))
        store.putDocument("cards", ConfigDocument("test_card", 0, buildJsonObject { put("cost", JsonPrimitive(4)) }))
        assertEquals("test_card", store.documents("cards").first().id)
        assertTrue(store.removeDocument("cards", "test_card"))
        assertTrue(!store.removeDocument("cards", "test_card"))

        val bundle = Json.parseToJsonElement(store.bundleJson()).jsonObject
        assertEquals(store.version(), bundle.getValue("version").jsonPrimitive.content.toLong())
        assertEquals("5000", bundle.getValue("settings").jsonObject.getValue("war.pointsWin").jsonPrimitive.content)
        val cards = bundle.getValue("collections").jsonObject.getValue("cards").jsonArray
        assertEquals(13, cards.size)
        assertEquals("zombie", cards.first().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(2, cards.first().jsonObject.getValue("body").jsonObject.getValue("cost").jsonPrimitive.content.toInt())
        val servers = bundle.getValue("collections").jsonObject.getValue("servers").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf(ConfigSeeds.GAME_TASK, ConfigSeeds.WAR_TASK), servers)
    }

    @Test
    fun `seed definitions are consistent`() {
        assertEquals(ConfigSeeds.settings.size, ConfigSeeds.settings.map { it.key }.toSet().size)
        ConfigSeeds.settings.forEach { it.normalize(it.default) }
    }
}
