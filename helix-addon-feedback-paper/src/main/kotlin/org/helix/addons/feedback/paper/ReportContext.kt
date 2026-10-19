package org.helix.addons.feedback.paper

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.round

/**
 * Everything the plugin knows about the reporting player and server at the moment of a report; filled from Bukkit
 * by [FeedbackPlugin] and turned into the contract's `context` object by [ContextBuilder].
 *
 * @property service `HELIX_SERVICE_ID` of this server.
 * @property task `HELIX_TASK` of this server.
 * @property world world name.
 * @property x block position x.
 * @property y block position y.
 * @property z block position z.
 * @property yaw view yaw.
 * @property pitch view pitch.
 * @property gamemode game mode name.
 * @property tps server TPS (last minute).
 * @property mspt average milliseconds per tick.
 * @property ping player ping in ms.
 * @property clientVersion client version as IGuard reports it, or the protocol guess.
 * @property clientBrand client brand.
 * @property clientFamily client family (vanilla, fabric, lunar, …) as IGuard classifies it.
 * @property labymod whether the client speaks LabyMod.
 * @property versions plugin name to version (plus `@commit` when the manifest names one).
 * @property screenshot whether the reporter asked for a screenshot.
 */
data class ContextSnapshot(
    val service: String,
    val task: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val gamemode: String,
    val tps: Double,
    val mspt: Double,
    val ping: Int,
    val clientVersion: String?,
    val clientBrand: String?,
    val clientFamily: String?,
    val labymod: Boolean,
    val versions: Map<String, String>,
    val screenshot: Boolean = false,
)

/** Builds the contract's `context` JSON (pure, unit-tested). */
object ContextBuilder {
    /**
     * @param snapshot the captured state.
     * @return the `context` object of `feedback.create` / `feedback.auto`.
     */
    fun toJson(snapshot: ContextSnapshot): JsonObject = buildJsonObject {
        put("service", snapshot.service)
        put("task", snapshot.task)
        put("world", snapshot.world)
        put("x", round1(snapshot.x))
        put("y", round1(snapshot.y))
        put("z", round1(snapshot.z))
        put("yaw", round1(snapshot.yaw.toDouble()))
        put("pitch", round1(snapshot.pitch.toDouble()))
        put("gamemode", snapshot.gamemode)
        put("tps", round1(snapshot.tps.coerceAtMost(20.0)))
        put("mspt", round1(snapshot.mspt))
        put("ping", snapshot.ping)
        putJsonObject("client") {
            put("version", snapshot.clientVersion ?: "unknown")
            put("brand", snapshot.clientBrand ?: "unknown")
            put("family", snapshot.clientFamily ?: "unknown")
            put("labymod", snapshot.labymod)
        }
        putJsonObject("versions") {
            snapshot.versions.toSortedMap().forEach { (name, version) -> put(name, version) }
        }
        put("screenshot", snapshot.screenshot)
    }

    /**
     * Turns a jar manifest's attributes into a version string: `Implementation-Version` (or the plugin version)
     * plus `@<commit>` when a `Git-Commit`/`Implementation-Build` attribute is present.
     *
     * @param pluginVersion version from plugin.yml.
     * @param attributes manifest main attributes (name to value).
     * @return the display version.
     */
    fun versionOf(pluginVersion: String, attributes: Map<String, String>): String {
        val version = attributes["Implementation-Version"]?.takeIf { it.isNotBlank() } ?: pluginVersion
        val commit = (attributes["Git-Commit"] ?: attributes["Implementation-Build"])?.takeIf { it.isNotBlank() }
        return if (commit != null) "$version@${commit.take(10)}" else version
    }

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0
}
