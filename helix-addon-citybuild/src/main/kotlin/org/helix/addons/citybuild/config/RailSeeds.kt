package org.helix.addons.citybuild.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Seeds of the rail network (game concept A21.4).
 *
 * `rail_lines` document body (contract with the Paper plugin, package `de.tytoss.citybuild.rail`): `name`,
 * `enabled`, `built` (whether the instances lay the track), `loop` (ring line), `points` (list of `{x, z}` road
 * junctions of the plot world, consecutive points share x or z), `trains` (list of compositions, each a list of
 * `models` ids with kind `train`, locomotive first), `count` (trains on the line), `speed` (blocks per second),
 * `shards` (instance ids, empty = all).
 *
 * `rail_stations` document body: `name`, `line` (optional line id), `world`, `x`, `y`, `z`, `yaw`, `starter` (known
 * to everybody), `model` (`models` id, default `haltestelle`), `enabled`, `shards`.
 */
object RailSeeds {
    /** Collection of the rail lines. */
    const val LINES: String = "rail_lines"

    /** Collection of the stations. */
    const val STATIONS: String = "rail_stations"

    /** Settings of the rail network. */
    val settings: List<SettingDefinition> = listOf(
        SettingDefinition("rail.viewRadius", "INT", "96", "Distance in blocks at which decorative trains are shown"),
        SettingDefinition("rail.discoverRadius", "INT", "8", "Distance in blocks at which a station counts as discovered"),
        SettingDefinition("rail.updateTicks", "INT", "2", "Ticks between two train position updates"),
        SettingDefinition("rail.buildBlocksPerTick", "INT", "256", "Track blocks laid or removed per tick"),
        SettingDefinition("rail.defaultSpeed", "DOUBLE", "6.0", "Speed of trains without own value (blocks per second)"),
    )

    /**
     * @return the seed documents of [LINES]: one disabled example ring around four junctions.
     */
    fun lines(): List<ConfigDocument> = listOf(
        ConfigDocument(
            "ring_mitte",
            0,
            buildJsonObject {
                put("name", "Ring Mitte")
                put("enabled", false)
                put("built", false)
                put("loop", true)
                put("points", buildJsonArray { listOf(0 to 0, 110 to 0, 110 to 110, 0 to 110).forEach { (x, z) -> add(point(x, z)) } })
                put("trains", buildJsonArray { add(buildJsonArray { listOf("dampflok", "personenwagen", "gueterwagen").forEach { add(JsonPrimitive(it)) } }) })
                put("count", 2)
                put("speed", 6.0)
                put("shards", JsonArray(emptyList()))
            },
        ),
    )

    /** @return the seed documents of [STATIONS] (none; the team places them in game). */
    fun stations(): List<ConfigDocument> = emptyList()

    private fun point(x: Int, z: Int): JsonObject = buildJsonObject { put("x", x); put("z", z) }
}
