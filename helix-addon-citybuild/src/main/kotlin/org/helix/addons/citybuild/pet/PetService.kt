package org.helix.addons.citybuild.pet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.helix.addons.citybuild.service.ActionRegistrar
import org.helix.addons.citybuild.service.CityContext
import org.helix.addons.citybuild.service.Results
import java.util.UUID

/**
 * Pets (concept A21.5): registering a redeemed pet whistle, listing, renaming, variant, mood bookkeeping and
 * releasing. Only the owner may change a pet; the Paper plugin computes the mood decay and sends the new value.
 *
 * @property ctx shared dependencies (configuration, clock).
 * @property store pet persistence.
 */
class PetService(private val ctx: CityContext, private val store: PetStore) {
    /**
     * Registers a pet from a redeemed whistle.
     *
     * @param uuid owner.
     * @param shard instance.
     * @param type catalog id (collection `models`, kind `pet`).
     * @param variant variant id or `null`.
     * @return the new pet.
     */
    fun register(uuid: String, shard: String, type: String, variant: String?): Pet {
        val entry = ctx.doc(MODELS, type) ?: Results.fail("unknown_model", "no pet model '$type'")
        val kind = entry["kind"]?.jsonPrimitive?.contentOrNull
        if (kind != "pet") Results.fail("invalid_model", "model '$type' is a $kind, not a pet")
        val max = ctx.int("pets.maxPerPlayer", DEFAULT_MAX)
        if (store.list(uuid, shard).size >= max) Results.fail("limit", "pet limit $max reached")
        val now = ctx.now()
        val pet = Pet(
            id = UUID.randomUUID().toString(),
            uuid = uuid,
            shard = shard,
            type = type,
            variant = variant?.takeIf { it in variants(entry) },
            mood = ctx.int("pets.startMood", DEFAULT_MOOD).coerceIn(0, MAX_MOOD),
            createdAt = now,
            updatedAt = now,
        )
        store.put(pet)
        ctx.log("pet", shard, uuid, pet.id, data = buildJsonObject { put("type", type) })
        return pet
    }

    /**
     * Pets of a player on an instance.
     *
     * @param uuid owner.
     * @param shard instance.
     * @return the pets.
     */
    fun list(uuid: String, shard: String): List<Pet> = store.list(uuid, shard)

    /**
     * Changes name, variant or mood; `fed: true` stamps the feeding time.
     *
     * @param uuid owner (must own the pet).
     * @param id pet id.
     * @param changes JSON with optional `name` (empty = catalog name), `variant`, `mood`, `fed`.
     * @return the updated pet.
     */
    fun update(uuid: String, id: String, changes: JsonObject): Pet {
        val pet = owned(uuid, id)
        val rawName = changes["name"]?.jsonPrimitive?.contentOrNull
        val name = rawName?.trim()?.let { validName(it) }
        val variant = changes["variant"]?.jsonPrimitive?.contentOrNull
        if (variant != null && variant !in variants(ctx.doc(MODELS, pet.type))) Results.fail("invalid_variant", "unknown variant '$variant'")
        val mood = changes["mood"]?.jsonPrimitive?.intOrNull
        if (mood != null && mood !in 0..MAX_MOOD) Results.fail("invalid_mood", "mood must be 0..$MAX_MOOD")
        val fed = changes["fed"]?.jsonPrimitive?.booleanOrNull == true
        val now = ctx.now()
        val updated = pet.copy(
            name = if (rawName != null) name?.ifEmpty { null } else pet.name,
            variant = variant ?: pet.variant,
            mood = mood ?: pet.mood,
            lastFed = if (fed) now else pet.lastFed,
            updatedAt = now,
        )
        store.put(updated)
        return updated
    }

    /**
     * Releases a pet for good.
     *
     * @param uuid owner.
     * @param id pet id.
     */
    fun remove(uuid: String, id: String) {
        val pet = owned(uuid, id)
        store.delete(pet.id)
        ctx.log("pet.remove", pet.shard, uuid, pet.id, data = buildJsonObject { put("type", pet.type) })
    }

    /**
     * Registers the `citybuild.pet.*` actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        r.register("citybuild.pet.register", "citybuild.pet.register <uuid> <shard> <type> [variant]", "Registers a redeemed pet whistle.", null) { a ->
            Results.ok(CityContext.json.encodeToJsonElement(Pet.serializer(), register(a.str(0, "uuid"), a.str(1, "shard"), a.str(2, "type"), a.opt(3))))
        }
        r.register("citybuild.pet.list", "citybuild.pet.list <uuid> <shard>", "Pets of a player on an instance.", null) { a ->
            Results.ok(JsonArray(list(a.str(0, "uuid"), a.str(1, "shard")).map { CityContext.json.encodeToJsonElement(Pet.serializer(), it) }))
        }
        r.register("citybuild.pet.update", "citybuild.pet.update <uuid> <id> <json>", "Changes name, variant or mood of a pet.", null) { a ->
            Results.ok(CityContext.json.encodeToJsonElement(Pet.serializer(), update(a.str(0, "uuid"), a.str(1, "id"), a.json(2))))
        }
        r.register("citybuild.pet.remove", "citybuild.pet.remove <uuid> <id>", "Releases a pet.", null) { a ->
            remove(a.str(0, "uuid"), a.str(1, "id"))
            Results.ok(buildJsonObject { put("removed", true) })
        }
    }

    private fun owned(uuid: String, id: String): Pet {
        val pet = store.get(id) ?: Results.fail("not_found", "no pet '$id'")
        if (pet.uuid != uuid) Results.fail("not_owner", "pet '$id' belongs to someone else")
        return pet
    }

    private fun validName(name: String): String {
        if (name.length > MAX_NAME) Results.fail("invalid_name", "name longer than $MAX_NAME characters")
        if (name.any { it == '<' || it == '>' || it == '&' || it == '§' }) Results.fail("invalid_name", "name contains formatting characters")
        return name
    }

    private fun variants(entry: JsonObject?): Set<String> =
        entry?.get("variants")?.let { v -> runCatching { v.jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }.getOrNull() }.orEmpty().toSet()

    private companion object {
        const val MODELS = "models"
        const val DEFAULT_MAX = 10
        const val DEFAULT_MOOD = 80
        const val MAX_MOOD = 100
        const val MAX_NAME = 24
    }
}
