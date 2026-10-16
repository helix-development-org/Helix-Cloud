package org.helix.addons.citybuild

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addons.citybuild.config.ConfigStore
import org.helix.addons.citybuild.config.ModelSeeds
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelSeedsTest {
    private val kinds = setOf("item", "hat", "food", "material", "furniture", "vehicle", "pet", "livestock", "creature", "train", "station")
    private val idPattern = Regex("[a-z0-9_]{1,40}")

    @Test
    fun `model catalog covers every kind of the concept with valid ids`() {
        val models = ModelSeeds.models()
        assertEquals(models.size, models.map { it.id }.toSet().size, "ids must be unique")
        models.forEach { assertTrue(idPattern.matches(it.id), "invalid id ${it.id}") }
        val byKind = models.groupBy { it.body.getValue("kind").jsonPrimitive.content }
        assertEquals(kinds, byKind.keys)
        assertEquals(8, byKind.getValue("item").size)
        assertEquals(8, byKind.getValue("hat").size)
        assertEquals(8, byKind.getValue("food").size)
        assertEquals(21, byKind.getValue("furniture").size)
        assertEquals(10, byKind.getValue("vehicle").size)
        assertEquals(8, byKind.getValue("pet").size)
        assertEquals(4, byKind.getValue("livestock").size)
        assertEquals(7, byKind.getValue("creature").size)
        val dealers = models.mapNotNull { it.body["dealer"]?.takeIf { d -> d is kotlinx.serialization.json.JsonPrimitive && d.isString }?.jsonPrimitive?.content }.toSet()
        assertEquals(setOf("moebel", "stellmacher", "tiere"), dealers)
        // vehicles carry their movement data
        val boat = models.single { it.id == "ruderboot" }.body.getValue("data").jsonObject
        assertEquals("water", boat.getValue("type").jsonPrimitive.content)
        assertEquals(2, boat.getValue("seats").jsonPrimitive.content.toInt())
    }

    @Test
    fun `references between models resolve`() {
        val ids = ModelSeeds.models().map { it.id }.toSet()
        ModelSeeds.models().forEach { doc ->
            val data = doc.body.getValue("data").jsonObject
            data["favoriteFood"]?.jsonPrimitive?.content?.let { assertTrue(it in ids, "${doc.id}: unknown favourite food $it") }
            data["yields"]?.jsonArray?.forEach { y -> assertTrue(y.jsonObject.getValue("model").jsonPrimitive.content in ids, "${doc.id}: unknown yield") }
            data["drops"]?.jsonArray?.forEach { d -> assertTrue(d.jsonObject.getValue("model").jsonPrimitive.content in ids, "${doc.id}: unknown drop") }
        }
        ModelSeeds.recipes().forEach { r ->
            val parts = r.body.getValue("inputs").jsonArray.map { it.jsonObject } + r.body.getValue("output").jsonObject
            parts.forEach { p ->
                val model = p["model"]?.takeIf { it is kotlinx.serialization.json.JsonPrimitive && it.isString }?.jsonPrimitive?.content
                if (model != null) assertTrue(model in ids, "${r.id}: unknown model $model")
            }
        }
    }

    @Test
    fun `store seeds models, recipes and the new tier limits`() {
        val store = ConfigStore(InMemoryAddonStorage())
        assertEquals(89, store.documents("models").size)
        assertEquals(8, store.documents("recipes").size)
        assertEquals("200", store.document("limits", "tier3")!!.body.getValue("furniture").jsonPrimitive.content)
        assertEquals("6", store.document("limits", "tier0")!!.body.getValue("livestock").jsonPrimitive.content)
        assertEquals("stellmacher", store.document("villagers", "kuno_kurbel")!!.body.getValue("role").jsonPrimitive.content)
    }
}
