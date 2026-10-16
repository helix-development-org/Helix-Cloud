package org.helix.addons.citybuild.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.helix.addons.citybuild.config.RailSeeds
import org.helix.addons.citybuild.store.RailStore
import org.helix.api.addon.PlayerDataProvider

/**
 * Rail network (game concept A21.4): station discovery per player and instance. Lines, stations and the
 * decorative trains are configuration (collections [RailSeeds.LINES] and [RailSeeds.STATIONS]) and run entirely
 * on the Paper side; the node only remembers which stations a player may teleport to.
 *
 * @property ctx shared dependencies (config store, clock).
 * @property store discovery persistence.
 * @property resolveUuid player name or uuid to uuid (GDPR provider), `null` when unknown.
 */
class RailService(
    private val ctx: CityContext,
    private val store: RailStore,
    private val resolveUuid: (String) -> String? = { it },
) {
    private val idPattern = Regex("[a-z0-9_]{1,40}")

    /**
     * Records a discovery.
     *
     * @param uuid player.
     * @param shard instance.
     * @param station station id; must exist in collection [RailSeeds.STATIONS].
     * @return `true` when the station was new for the player.
     */
    fun discover(uuid: String, shard: String, station: String): Boolean {
        if (!idPattern.matches(station)) Results.fail("invalid_station", "station id must match ${idPattern.pattern}")
        if (ctx.config.document(RailSeeds.STATIONS, station) == null) Results.fail("not_found", "no station '$station'")
        return store.discover(uuid, shard, station, ctx.now())
    }

    /**
     * @param uuid player.
     * @param shard instance.
     * @return discovered station ids.
     */
    fun discovered(uuid: String, shard: String): List<String> = store.discovered(uuid, shard)

    /** GDPR export/delete of the discoveries. */
    val dataProvider: PlayerDataProvider = object : PlayerDataProvider {
        override fun export(player: String): String? {
            val uuid = resolveUuid(player) ?: return null
            val all = store.export(uuid).takeIf { it.isNotEmpty() } ?: return null
            return buildJsonObject {
                put("railDiscovered", buildJsonObject { all.forEach { (shard, ids) -> put(shard, JsonArray(ids.map(::JsonPrimitive))) } })
            }.toString()
        }

        override fun delete(player: String): Boolean = resolveUuid(player)?.let(store::forget) ?: false
    }

    /**
     * Registers the rail actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        r.register("citybuild.rail.discovered", "citybuild.rail.discovered <uuid> <shard>", "Stations a player discovered on an instance.", null) { a ->
            Results.ok(JsonArray(discovered(a.str(0, "uuid"), a.str(1, "shard")).map(::JsonPrimitive)))
        }
        r.register("citybuild.rail.discover", "citybuild.rail.discover <uuid> <shard> <station>", "Marks a station as discovered.", null) { a ->
            val station = a.str(2, "station")
            val added = discover(a.str(0, "uuid"), a.str(1, "shard"), station)
            Results.ok(buildJsonObject { put("station", station); put("new", added) })
        }
    }
}
