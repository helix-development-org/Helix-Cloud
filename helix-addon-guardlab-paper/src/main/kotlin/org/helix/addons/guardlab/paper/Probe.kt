package org.helix.addons.guardlab.paper

import de.tytoss.iguard.api.IGuardApi
import de.tytoss.iguard.api.IGuardFrameReport
import de.tytoss.iguard.api.IGuardTraceSession
import org.bukkit.Server
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Path
import java.util.UUID

/**
 * A running IGuard trace recording of one scenario.
 */
interface ProbeTrace {
    /** The JSONL file IGuard writes (completed asynchronously after [stop]). */
    val file: Path

    /** False once the recording ended (stopped, or IGuard's size/duration limit was reached). */
    val active: Boolean

    /**
     * Writes a marker line into the recording.
     *
     * @param text marker text.
     * @return false when the recording no longer runs.
     */
    fun marker(text: String): Boolean

    /**
     * Stops the recording.
     *
     * @return the file, or null when it had already ended.
     */
    fun stop(): Path?
}

/**
 * Access to IGuard for the runner.
 *
 * The evaluation reads IGuard's frame reports ([onFrames]: per-check failed/counted, exemptions and
 * environment tags of every processed packet). [levels] remains for the cooldown between scenarios and
 * as a fallback when no frame reports arrive (rises per tick, [ViolationTracker]). [startTrace] records
 * IGuard's own trace file per scenario.
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

    /**
     * Starts IGuard's trace recording for a player (a running one is replaced).
     *
     * @param playerId the player.
     * @param label file label, e.g. `<suite>-<scenario>`.
     * @return the recording, or null when the probe cannot record.
     */
    fun startTrace(playerId: UUID, label: String): ProbeTrace? = null

    /**
     * Registers a listener for IGuard's frame reports of all players. It runs on IGuard's worker threads:
     * it must be thread-safe and must not touch the Bukkit API.
     *
     * @param listener receives every frame.
     * @return handle to unregister, or null when the probe delivers no frame reports.
     */
    fun onFrames(listener: (FrameSample) -> Unit): AutoCloseable? = null
}

/**
 * [GuardProbe] over [IGuardApi]: frame reports via `addTraceListener`, trace files via `startTrace`,
 * levels via `snapshot`, alert levels and sanctions mode from IGuard's `config.yml`.
 *
 * Only instantiate when the IGuard plugin is enabled – this class links against IGuard's API.
 *
 * @property api the IGuard service.
 * @property configFile `plugins/IGuard/config.yml`.
 */
class IGuardApiProbe(private val api: IGuardApi, private val configFile: File) : GuardProbe {
    private val config = runCatching { YamlConfiguration.loadConfiguration(configFile) }.getOrNull()

    /** Description for the report. */
    override val description: String = "IGuardApi: Frame-Berichte (failed/counted je Check, Exemptions, Tags) + IGuard-Trace je Szenario"

    /** Reads the snapshot's levels. */
    override fun levels(playerId: UUID): Map<String, Double> = api.snapshot(playerId)?.violationLevels.orEmpty()

    /** Reads `checks.<id>.alert-vl` (default 5). */
    override fun alertVl(check: String): Double = config?.getDouble("checks.$check.alert-vl", DEFAULT_ALERT_VL) ?: DEFAULT_ALERT_VL

    /** Reads `sanctions.mode`. */
    override val sanctionsMode: String = config?.getString("sanctions.mode") ?: "unbekannt"

    /** Starts [IGuardApi.startTrace]. */
    override fun startTrace(playerId: UUID, label: String): ProbeTrace = SessionTrace(api.startTrace(playerId, label))

    /** Registers an [IGuardApi.addTraceListener] that converts every report. */
    override fun onFrames(listener: (FrameSample) -> Unit): AutoCloseable = api.addTraceListener { report -> listener(report.toSample()) }

    private class SessionTrace(private val session: IGuardTraceSession) : ProbeTrace {
        override val file: Path get() = session.file
        override val active: Boolean get() = session.active
        override fun marker(text: String): Boolean = session.marker(text)
        override fun stop(): Path? = session.stop()
    }

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

        private fun IGuardFrameReport.toSample() = FrameSample(
            playerId = playerId,
            type = frameType,
            clientTick = clientTick,
            exempt = exempt,
            exemptions = exemptions,
            tags = environmentTags,
            results = results.map {
                CheckSample(
                    it.checkId, it.failed, it.counted, it.weight, it.violationLevel, it.alertLevel,
                    if (it.failed) it.evidence.mapValues { (_, value) -> evidenceText(value) } else emptyMap(),
                )
            },
        )

        private fun evidenceText(value: Any?): String = when (value) {
            is Double, is Float -> String.format(java.util.Locale.ROOT, "%.3f", (value as Number).toDouble())
            else -> value.toString()
        }
    }
}
