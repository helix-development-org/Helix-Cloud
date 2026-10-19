package org.helix.addons.citybuild.pet

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A pet a player owns on one instance (concept A21.5). Pets are redeemed from a pet whistle and, like all items, bound
 * to their instance. At most one pet per player is out at a time; which one is a matter of the Paper plugin.
 *
 * @property id pet id (uuid).
 * @property uuid owner.
 * @property shard instance.
 * @property type catalog id of the pet model (`models` collection, kind `pet`).
 * @property name display name chosen by the owner (`null` = the catalog name).
 * @property variant colour/shape variant (`null` = default).
 * @property mood mood 0..100 (care pets).
 * @property lastFed last feeding (epoch ms, 0 = never).
 * @property createdAt registration time (epoch ms).
 * @property updatedAt last change (epoch ms).
 */
@Serializable
data class Pet(
    val id: String,
    val uuid: String,
    val shard: String,
    val type: String,
    val name: String? = null,
    val variant: String? = null,
    val mood: Int = 80,
    val lastFed: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * Persistence of [Pet]s, independent of the main CityBuild repository.
 */
interface PetStore : AutoCloseable {
    /**
     * Pets of a player on an instance, oldest first.
     *
     * @param uuid owner.
     * @param shard instance.
     * @return the pets.
     */
    fun list(uuid: String, shard: String): List<Pet>

    /**
     * A pet by id.
     *
     * @param id pet id.
     * @return the pet or `null`.
     */
    fun get(id: String): Pet?

    /**
     * Creates or replaces a pet (same id).
     *
     * @param pet the pet.
     */
    fun put(pet: Pet)

    /**
     * Deletes a pet.
     *
     * @param id pet id.
     * @return whether it existed.
     */
    fun delete(id: String): Boolean

    /** Releases resources. */
    /**
     * Counts or deletes every pet record of every instance (release reset).
     *
     * @param dryRun `true` only counts.
     * @return number of records (counted before deletion).
     */
    fun wipe(dryRun: Boolean): Int

    override fun close() {}
}

/**
 * [PetStore] in the addon document store (one document `data.pets`); every method is synchronized.
 *
 * @property storage addon document storage.
 */
class DocumentPetStore(private val storage: org.helix.api.storage.AddonStorage) : PetStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val rows: MutableList<Pet> =
        storage.read(KEY)?.let { json.decodeFromString(ListSerializer(Pet.serializer()), it).toMutableList() } ?: mutableListOf()

    @Synchronized override fun list(uuid: String, shard: String): List<Pet> =
        rows.filter { it.uuid == uuid && it.shard == shard }.sortedBy { it.createdAt }

    @Synchronized override fun get(id: String): Pet? = rows.firstOrNull { it.id == id }

    @Synchronized override fun put(pet: Pet) {
        rows.removeAll { it.id == pet.id }
        rows += pet
        save()
    }

    @Synchronized override fun delete(id: String): Boolean = rows.removeAll { it.id == id }.also { if (it) save() }

    @Synchronized override fun wipe(dryRun: Boolean): Int = rows.size.also { if (!dryRun && it > 0) { rows.clear(); save() } }

    private fun save() = storage.write(KEY, json.encodeToString(ListSerializer(Pet.serializer()), rows))

    private companion object {
        const val KEY = "data.pets"
    }
}
