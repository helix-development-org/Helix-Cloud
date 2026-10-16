package org.helix.addons.citybuild

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.helix.addons.citybuild.config.ConfigDocument
import org.helix.addons.citybuild.config.ConfigSeeds
import org.helix.addons.citybuild.config.SeedCollections
import org.helix.addons.citybuild.pet.DocumentPetStore
import org.helix.addons.citybuild.pet.PetService
import org.helix.addons.citybuild.service.CityException
import org.helix.api.storage.InMemoryAddonStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PetServiceTest {
    private val clock = TestClock()
    private val ctx = testContext(clock)
    private val storage = InMemoryAddonStorage()
    private val service = PetService(ctx, DocumentPetStore(storage))

    init {
        ctx.config.putDocument(
            "models",
            ConfigDocument(
                "katze",
                0,
                buildJsonObject {
                    put("kind", "pet")
                    put("variants", buildJsonArray { add(JsonPrimitive("rot")); add(JsonPrimitive("grau")) })
                },
            ),
        )
        ctx.config.putDocument("models", ConfigDocument("ruderboot", 1, buildJsonObject { put("kind", "vehicle") }))
    }

    @Test
    fun `registers pets per instance with start mood`() {
        val pet = service.register(ALICE, SHARD, "katze", "grau")
        assertEquals("grau", pet.variant)
        assertEquals(80, pet.mood)
        assertEquals(listOf(pet), service.list(ALICE, SHARD))
        assertTrue(service.list(ALICE, "CityBuild-2").isEmpty())
        assertNull(service.register(ALICE, SHARD, "katze", "lila").variant)
    }

    @Test
    fun `rejects unknown and non-pet models and enforces the limit`() {
        assertEquals("unknown_model", assertFailsWith<CityException> { service.register(ALICE, SHARD, "nix", null) }.code)
        assertEquals("invalid_model", assertFailsWith<CityException> { service.register(ALICE, SHARD, "ruderboot", null) }.code)
        ctx.config.setSetting("pets.maxPerPlayer", "1")
        service.register(ALICE, SHARD, "katze", null)
        assertEquals("limit", assertFailsWith<CityException> { service.register(ALICE, SHARD, "katze", null) }.code)
    }

    @Test
    fun `owner updates name variant mood and feeding`() {
        val pet = service.register(ALICE, SHARD, "katze", null)
        assertEquals("not_owner", assertFailsWith<CityException> { service.update(BOB, pet.id, buildJsonObject { put("mood", 10) }) }.code)
        assertEquals("invalid_mood", assertFailsWith<CityException> { service.update(ALICE, pet.id, buildJsonObject { put("mood", 101) }) }.code)
        assertEquals("invalid_name", assertFailsWith<CityException> { service.update(ALICE, pet.id, buildJsonObject { put("name", "<red>Mieze") }) }.code)
        assertEquals("invalid_variant", assertFailsWith<CityException> { service.update(ALICE, pet.id, buildJsonObject { put("variant", "lila") }) }.code)
        clock.advanceSeconds(5)
        val updated = service.update(ALICE, pet.id, buildJsonObject { put("name", "Mieze"); put("variant", "rot"); put("mood", 40); put("fed", true) })
        assertEquals("Mieze", updated.name)
        assertEquals("rot", updated.variant)
        assertEquals(40, updated.mood)
        assertEquals(clock.millis(), updated.lastFed)
        assertNull(service.update(ALICE, pet.id, buildJsonObject { put("name", "") }).name)
    }

    @Test
    fun `remove deletes only own pets`() {
        val pet = service.register(ALICE, SHARD, "katze", null)
        assertEquals("not_owner", assertFailsWith<CityException> { service.remove(BOB, pet.id) }.code)
        service.remove(ALICE, pet.id)
        assertTrue(service.list(ALICE, SHARD).isEmpty())
        assertEquals("not_found", assertFailsWith<CityException> { service.remove(ALICE, pet.id) }.code)
    }

    @Test
    fun `seeds contain pet, livestock and creature settings plus boss rewards and job values`() {
        val keys = ConfigSeeds.settings.map { it.key }.toSet()
        listOf("pets.moodDecayPerHour", "livestock.feedMinutes", "creatures.replaceChance", "boss.time", "boss.minDamageShare").forEach { assertTrue(it in keys, it) }
        val all = SeedCollections.all()
        assertTrue(all.getValue("boss_rewards").isNotEmpty())
        val jobs = all.getValue("jobs").associateBy { it.id }
        assertTrue(jobs.getValue("farmer").body.toString().contains("collect:kronland_huhn"))
        assertTrue(jobs.getValue("jaeger").body.toString().contains("kill:creature:waldriese"))
    }
}
