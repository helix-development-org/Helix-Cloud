package org.helix.addons.guardlab.paper

import de.tytoss.iguard.api.IGuardApi
import org.bukkit.Server
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

/**
 * Reads IGuard's per-check violation levels for a player.
 *
 * GuardLab samples every tick and turns rises into flags ([ViolationTracker]), so it needs nothing from
 * IGuard beyond its public API. A richer trace API (per-check results with details) can be plugged in
 * behind this interface later.
 */
interface GuardProbe {
    /** Short description for the report header. */
    val description: String

    /**
     * Current violation levels.
     *
     * @param playerId the player.
     * @return check id → level (empty before IGuard saw the player).
     */
    fun levels(playerId: UUID): Map<String, Double>

    /**
     * Alert level of a check (rises at or above it count as false alarms in legit scenarios).
     *
     * @param check check id.
     * @return the level.
     */
    fun alertVl(check: String): Double

    /** IGuard's sanctions mode (`shadow`, `enforce`, or `unbekannt`). */
    val sanctionsMode: String
}

/**
 * [GuardProbe] over [IGuardApi.snapshot] and IGuard's `config.yml`.
 *
 * Only instantiate when the IGuard plugin is enabled – this class links against IGuard's API.
 *
 * @property api the IGuard service.
 * @property configFile `plugins/IGuard/config.yml`.
 */
class IGuardApiProbe(private val api: IGuardApi, private val configFile: File) : GuardProbe {
    private val config = runCatching { YamlConfiguration.loadConfiguration(configFile) }.getOrNull()

    /** Description for the report. */
    override val description: String = "IGuardApi.snapshot (VL-Anstiege pro Tick)"

    /** Reads the snapshot's levels. */
    override fun levels(playerId: UUID): Map<String, Double> = api.snapshot(playerId)?.violationLevels.orEmpty()

    /** Reads `checks.<id>.alert-vl` (default 5). */
    override fun alertVl(check: String): Double = config?.getDouble("checks.$check.alert-vl", DEFAULT_ALERT_VL) ?: DEFAULT_ALERT_VL

    /** Reads `sanctions.mode`. */
    override val sanctionsMode: String = config?.getString("sanctions.mode") ?: "unbekannt"

    /** Factory guarded against a missing IGuard. */
    companion object {
        private const val DEFAULT_ALERT_VL = 5.0

        /**
         * Creates the probe when IGuard is enabled and registered its service.
         *
         * @param server the server.
         * @return the probe or null.
         */
        fun create(server: Server): GuardProbe? {
            val plugin = server.pluginManager.getPlugin("IGuard")?.takeIf { it.isEnabled } ?: return null
            val api = server.servicesManager.load(IGuardApi::class.java) ?: return null
            return IGuardApiProbe(api, File(plugin.dataFolder, "config.yml"))
        }
    }
}
