package org.helix.addons.citybuild.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.helix.addons.citybuild.model.Home
import org.helix.addons.citybuild.model.Warp

/**
 * Homes (per player and instance, limited by tier) and warps (per instance, set by the team).
 *
 * @property ctx shared dependencies.
 */
class HomeService(private val ctx: CityContext) {
    private val namePattern = Regex("[A-Za-z0-9_-]{1,16}")

    /**
     * Lists the homes of a player.
     *
     * @param uuid player.
     * @param shard instance.
     * @return the homes.
     */
    fun homes(uuid: String, shard: String): List<Home> = ctx.repository.homes(uuid, shard)

    /**
     * Sets (creates or moves) a home.
     *
     * @param home the home; its name must match `[A-Za-z0-9_-]{1,16}` and its world `homes.allowedWorlds`.
     * @param limit maximum number of homes of the player's tier.
     * @return the stored home.
     */
    fun setHome(home: Home, limit: Int): Home {
        if (!namePattern.matches(home.name)) Results.fail("invalid_name", "home name must match ${namePattern.pattern}")
        val allowed = ctx.strings("homes.allowedWorlds")
        if (allowed.isNotEmpty() && allowed.none { it.equals(home.world, true) }) Results.fail("world_not_allowed", "homes are not allowed in ${home.world}")
        val existing = ctx.repository.homes(home.uuid, home.shard)
        val replaces = existing.any { it.name.equals(home.name, true) }
        if (!replaces && existing.size >= limit) Results.fail("limit", "home limit $limit reached")
        ctx.repository.putHome(home)
        return home
    }

    /**
     * Deletes a home.
     *
     * @param uuid player.
     * @param shard instance.
     * @param name home name.
     */
    fun deleteHome(uuid: String, shard: String, name: String) {
        if (!ctx.repository.deleteHome(uuid, shard, name)) Results.fail("not_found", "no home '$name'")
    }

    /**
     * Sets a warp.
     *
     * @param warp the warp.
     * @return the stored warp.
     */
    fun setWarp(warp: Warp): Warp {
        if (!namePattern.matches(warp.name)) Results.fail("invalid_name", "warp name must match ${namePattern.pattern}")
        ctx.repository.putWarp(warp)
        return warp
    }

    /**
     * Registers the home and warp actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        r.register("citybuild.home.list", "citybuild.home.list <uuid> <shard>", "Homes of a player on an instance.", null) { a ->
            Results.ok(CityContext.json.encodeToJsonElement(homes(a.str(0, "uuid"), a.str(1, "shard"))))
        }
        r.register("citybuild.home.set", "citybuild.home.set <uuid> <shard> <limit> <json>", "Creates or moves a home within the tier limit.", null) { a ->
            val body = a.json(3)
            val home = CityContext.json.decodeFromJsonElement(Home.serializer(), body.with("uuid" to a.str(0, "uuid"), "shard" to a.str(1, "shard")))
            Results.ok(CityContext.json.encodeToJsonElement(setHome(home, a.int(2, "limit"))))
        }
        r.register("citybuild.home.delete", "citybuild.home.delete <uuid> <shard> <name>", "Deletes a home.", null) { a ->
            deleteHome(a.str(0, "uuid"), a.str(1, "shard"), a.str(2, "name"))
            Results.ok(buildJsonObject { put("deleted", true) })
        }
        r.register("citybuild.warp.list", "citybuild.warp.list <shard>", "Warps of an instance.", null) { a ->
            Results.ok(JsonArray(ctx.repository.warps(a.str(0, "shard")).map { CityContext.json.encodeToJsonElement(it) }))
        }
        r.register("citybuild.warp.set", "citybuild.warp.set <shard> <json>", "Creates or moves a warp.", "citybuild.admin.warps") { a ->
            val warp = CityContext.json.decodeFromJsonElement(Warp.serializer(), a.json(1).with("shard" to a.str(0, "shard")))
            Results.ok(CityContext.json.encodeToJsonElement(setWarp(warp)))
        }
        r.register("citybuild.warp.delete", "citybuild.warp.delete <shard> <name>", "Deletes a warp.", "citybuild.admin.warps") { a ->
            if (!ctx.repository.deleteWarp(a.str(0, "shard"), a.str(1, "name"))) Results.fail("not_found", "no such warp")
            Results.ok(buildJsonObject { put("deleted", true) })
        }
    }
}
