package org.helix.addons.citybuild.vehicle

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Vehicle states: in the garage (not in any world) or standing in a world of its instance.
 */
object VehicleStates {
    /** Stored in the garage. */
    const val GARAGE: String = "garage"

    /** Spawned in a world (driven or parked). */
    const val WORLD: String = "world"

    /** All valid states. */
    val ALL: Set<String> = setOf(GARAGE, WORLD)
}

/**
 * Who may ride along in a vehicle (the driver is always the owner).
 */
object VehicleAccess {
    /** Everybody may ride along. */
    const val ALL: String = "all"

    /** Members/trusted players of the owner's plots. */
    const val MEMBERS: String = "members"

    /** Nobody but the owner. */
    const val NONE: String = "none"

    /** All valid access levels. */
    val VALUES: Set<String> = setOf(ALL, MEMBERS, NONE)
}

/**
 * A vehicle in a player's garage on one instance (concept A21.3). Ownership is per instance like all items.
 *
 * @property id vehicle id (uuid).
 * @property uuid owner.
 * @property shard instance.
 * @property model catalog id of the vehicle model (`models` collection, kind `vehicle`).
 * @property color index of the colour variant.
 * @property locked locked vehicles cannot be entered by anybody but the owner.
 * @property access ride-along permission, see [VehicleAccess].
 * @property state [VehicleStates.GARAGE] or [VehicleStates.WORLD].
 * @property world world while spawned.
 * @property x x coordinate while spawned.
 * @property y y coordinate while spawned.
 * @property z z coordinate while spawned.
 * @property createdAt registration time (epoch ms).
 * @property updatedAt last change (epoch ms).
 */
@Serializable
data class Vehicle(
    val id: String,
    val uuid: String,
    val shard: String,
    val model: String,
    val color: Int = 0,
    val locked: Boolean = false,
    val access: String = VehicleAccess.MEMBERS,
    val state: String = VehicleStates.GARAGE,
    val world: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * Persistence of [Vehicle]s, independent of the main CityBuild repository.
 */
interface VehicleStore : AutoCloseable {
    /**
     * Vehicles of a player on an instance, oldest first.
     *
     * @param uuid owner.
     * @param shard instance.
     * @return the vehicles.
     */
    fun list(uuid: String, shard: String): List<Vehicle>

    /**
     * A vehicle by id.
     *
     * @param id vehicle id.
     * @return the vehicle or `null`.
     */
    fun get(id: String): Vehicle?

    /**
     * Creates or replaces a vehicle (same id).
     *
     * @param vehicle the vehicle.
     */
    fun put(vehicle: Vehicle)

    /**
     * Deletes a vehicle.
     *
     * @param id vehicle id.
     * @return whether it existed.
     */
    fun delete(id: String): Boolean

    /**
     * Moves every vehicle of an instance that is marked as spawned back into the garage (instance start).
     *
     * @param shard instance.
     * @param at change time (epoch ms).
     * @return number of vehicles moved.
     */
    fun recallAll(shard: String, at: Long): Int

    /** Releases resources. */
    /**
     * Counts or deletes every vehicle record of every instance (release reset).
     *
     * @param dryRun `true` only counts.
     * @return number of records (counted before deletion).
     */
    fun wipe(dryRun: Boolean): Int

    override fun close() {}
}

/**
 * [VehicleStore] in the addon document store (one document `data.vehicles`); every method is synchronized.
 *
 * @property storage addon document storage.
 */
class DocumentVehicleStore(private val storage: org.helix.api.storage.AddonStorage) : VehicleStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val rows: MutableList<Vehicle> =
        storage.read(KEY)?.let { json.decodeFromString(ListSerializer(Vehicle.serializer()), it).toMutableList() } ?: mutableListOf()

    @Synchronized override fun list(uuid: String, shard: String): List<Vehicle> =
        rows.filter { it.uuid == uuid && it.shard == shard }.sortedBy { it.createdAt }

    @Synchronized override fun get(id: String): Vehicle? = rows.firstOrNull { it.id == id }

    @Synchronized override fun put(vehicle: Vehicle) {
        rows.removeAll { it.id == vehicle.id }
        rows += vehicle
        save()
    }

    @Synchronized override fun delete(id: String): Boolean = rows.removeAll { it.id == id }.also { if (it) save() }

    @Synchronized override fun recallAll(shard: String, at: Long): Int {
        var moved = 0
        rows.replaceAll { v ->
            if (v.shard == shard && v.state == VehicleStates.WORLD) {
                moved++
                v.copy(state = VehicleStates.GARAGE, world = null, x = null, y = null, z = null, updatedAt = at)
            } else {
                v
            }
        }
        if (moved > 0) save()
        return moved
    }

    @Synchronized override fun wipe(dryRun: Boolean): Int = rows.size.also { if (!dryRun && it > 0) { rows.clear(); save() } }

    private fun save() = storage.write(KEY, json.encodeToString(ListSerializer(Vehicle.serializer()), rows))

    private companion object {
        const val KEY = "data.vehicles"
    }
}
