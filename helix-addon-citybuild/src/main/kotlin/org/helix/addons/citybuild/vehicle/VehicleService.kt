package org.helix.addons.citybuild.vehicle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
 * Garage of the vehicles (concept A21.3): registering a vehicle licence, listing, repainting, locking, ride-along
 * permission, spawn/park bookkeeping and scrapping. Only the owner may change a vehicle.
 *
 * @property ctx shared dependencies (configuration, clock).
 * @property store vehicle persistence.
 */
class VehicleService(private val ctx: CityContext, private val store: VehicleStore) {
    /**
     * Registers a vehicle from a redeemed licence.
     *
     * @param uuid owner.
     * @param shard instance.
     * @param model catalog id (collection `models`, kind `vehicle`).
     * @param color colour variant index.
     * @return the new vehicle.
     */
    fun register(uuid: String, shard: String, model: String, color: Int = 0): Vehicle {
        val entry = ctx.doc(MODELS, model) ?: Results.fail("unknown_model", "no vehicle model '$model'")
        val kind = entry["kind"]?.jsonPrimitive?.contentOrNull
        if (kind != null && kind != "vehicle") Results.fail("invalid_model", "model '$model' is a $kind, not a vehicle")
        val max = ctx.int("vehicles.maxPerPlayer", DEFAULT_MAX)
        if (store.list(uuid, shard).size >= max) Results.fail("limit", "vehicle limit $max reached")
        val now = ctx.now()
        val vehicle = Vehicle(
            id = UUID.randomUUID().toString(),
            uuid = uuid,
            shard = shard,
            model = model,
            color = color.coerceIn(0, colorCount(entry) - 1),
            createdAt = now,
            updatedAt = now,
        )
        store.put(vehicle)
        ctx.log("vehicle", shard, uuid, vehicle.id, data = buildJsonObject { put("model", model) })
        return vehicle
    }

    /**
     * Vehicles of a player on an instance.
     *
     * @param uuid owner.
     * @param shard instance.
     * @return the vehicles.
     */
    fun list(uuid: String, shard: String): List<Vehicle> = store.list(uuid, shard)

    /**
     * Changes colour, lock or ride-along permission.
     *
     * @param uuid owner (must own the vehicle).
     * @param id vehicle id.
     * @param changes JSON with optional `color`, `locked`, `access`.
     * @return the updated vehicle.
     */
    fun update(uuid: String, id: String, changes: JsonObject): Vehicle {
        val vehicle = owned(uuid, id)
        val access = changes["access"]?.jsonPrimitive?.contentOrNull?.lowercase()
        if (access != null && access !in VehicleAccess.VALUES) Results.fail("invalid_access", "access must be one of ${VehicleAccess.VALUES}")
        val color = changes["color"]?.jsonPrimitive?.intOrNull
        if (color != null) {
            val count = colorCount(ctx.doc(MODELS, vehicle.model))
            if (color !in 0 until count) Results.fail("invalid_color", "colour must be 0..${count - 1}")
        }
        val updated = vehicle.copy(
            color = color ?: vehicle.color,
            locked = changes["locked"]?.jsonPrimitive?.booleanOrNull ?: vehicle.locked,
            access = access ?: vehicle.access,
            updatedAt = ctx.now(),
        )
        store.put(updated)
        return updated
    }

    /**
     * Records where a vehicle is: back in the garage or spawned at a position.
     *
     * @param uuid owner.
     * @param id vehicle id.
     * @param state [VehicleStates.GARAGE] or [VehicleStates.WORLD].
     * @param world world (spawned only).
     * @param x x coordinate (spawned only).
     * @param y y coordinate (spawned only).
     * @param z z coordinate (spawned only).
     * @return the updated vehicle.
     */
    fun park(uuid: String, id: String, state: String, world: String?, x: Double?, y: Double?, z: Double?): Vehicle {
        if (state !in VehicleStates.ALL) Results.fail("invalid_state", "state must be one of ${VehicleStates.ALL}")
        val vehicle = owned(uuid, id)
        val spawned = state == VehicleStates.WORLD
        if (spawned && (world == null || x == null || y == null || z == null)) Results.fail("invalid_position", "a spawned vehicle needs world x y z")
        val updated = vehicle.copy(
            state = state,
            world = if (spawned) world else null,
            x = if (spawned) x else null,
            y = if (spawned) y else null,
            z = if (spawned) z else null,
            updatedAt = ctx.now(),
        )
        store.put(updated)
        return updated
    }

    /**
     * Scraps a vehicle for good.
     *
     * @param uuid owner.
     * @param id vehicle id.
     */
    fun remove(uuid: String, id: String) {
        val vehicle = owned(uuid, id)
        store.delete(vehicle.id)
        ctx.log("vehicle.remove", vehicle.shard, uuid, vehicle.id, data = buildJsonObject { put("model", vehicle.model) })
    }

    /**
     * Moves all spawned vehicles of an instance back into their garages (called when an instance starts).
     *
     * @param shard instance.
     * @return number of recalled vehicles.
     */
    fun recall(shard: String): Int = store.recallAll(shard, ctx.now())

    /**
     * Registers the `citybuild.vehicle.*` actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        r.register("citybuild.vehicle.register", "citybuild.vehicle.register <uuid> <shard> <model> [color]", "Registers a redeemed vehicle licence in the garage.", null) { a ->
            Results.ok(CityContext.json.encodeToJsonElement(Vehicle.serializer(), register(a.str(0, "uuid"), a.str(1, "shard"), a.str(2, "model"), a.intOr(3, 0))))
        }
        r.register("citybuild.vehicle.list", "citybuild.vehicle.list <uuid> <shard>", "Vehicles of a player on an instance.", null) { a ->
            Results.ok(JsonArray(list(a.str(0, "uuid"), a.str(1, "shard")).map { CityContext.json.encodeToJsonElement(Vehicle.serializer(), it) }))
        }
        r.register("citybuild.vehicle.update", "citybuild.vehicle.update <uuid> <id> <json>", "Changes colour, lock or ride-along permission.", null) { a ->
            Results.ok(CityContext.json.encodeToJsonElement(Vehicle.serializer(), update(a.str(0, "uuid"), a.str(1, "id"), a.json(2))))
        }
        r.register("citybuild.vehicle.park", "citybuild.vehicle.park <uuid> <id> <garage|world> [world x y z]", "Records whether a vehicle is in the garage or spawned.", null) { a ->
            val vehicle = park(
                a.str(0, "uuid"),
                a.str(1, "id"),
                a.str(2, "state").lowercase(),
                a.opt(3),
                a.opt(4)?.toDoubleOrNull(),
                a.opt(5)?.toDoubleOrNull(),
                a.opt(6)?.toDoubleOrNull(),
            )
            Results.ok(CityContext.json.encodeToJsonElement(Vehicle.serializer(), vehicle))
        }
        r.register("citybuild.vehicle.remove", "citybuild.vehicle.remove <uuid> <id>", "Scraps a vehicle.", null) { a ->
            remove(a.str(0, "uuid"), a.str(1, "id"))
            Results.ok(buildJsonObject { put("removed", true) })
        }
        r.register("citybuild.vehicle.recall", "citybuild.vehicle.recall <shard>", "Moves all spawned vehicles of an instance back into the garage.", null) { a ->
            Results.ok(buildJsonObject { put("recalled", recall(a.str(0, "shard"))) })
        }
    }

    private fun owned(uuid: String, id: String): Vehicle {
        val vehicle = store.get(id) ?: Results.fail("not_found", "no vehicle '$id'")
        if (vehicle.uuid != uuid) Results.fail("not_owner", "vehicle '$id' belongs to someone else")
        return vehicle
    }

    private fun colorCount(entry: JsonObject?): Int {
        val data = entry?.get("data") as? JsonObject
        val colors = (data?.get("colors") ?: entry?.get("variants"))?.let { runCatching { it.jsonArray.size }.getOrNull() }
        return (colors ?: 1).coerceAtLeast(1)
    }

    private companion object {
        const val MODELS = "models"
        const val DEFAULT_MAX = 20
    }
}
