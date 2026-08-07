package de.tytoss.iguard.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin

/** Check-engine worker pool sizing (static: the pool is built once on startup). */
data class WorkerConfig(val stripes: Int, val queueCapacity: Int)

/** Write-queue sizing and retention for the node-backed history store. */
data class HistoryConfig(
    val queueCapacity: Int,
    val batchSize: Int,
    val flushMillis: Long,
    val retentionDays: Int,
)

/** Grace windows and thresholds that temporarily exempt players from checks (static). */
data class ExemptionConfig(
    val overloadMillis: Long,
    val teleportMillis: Long,
    val velocityMillis: Long,
    val respawnMillis: Long,
    val worldChangeMillis: Long,
    val lowTpsThreshold: Double,
    val snapshotMaxAgeMillis: Long,
)

/** Main-thread sampler budget (players per tick + hard nano cap). */
data class SamplerConfig(
    val maxPlayersPerTick: Int,
    val maxNanosPerTick: Long,
)

/** Incident/replay recording windows and confidence gating for detections (static). */
data class DetectionConfig(
    val incidentGapMillis: Long,
    val replayPreMillis: Long,
    val replayPostMillis: Long,
    val replayRetentionDays: Int,
    val replayMaxBytes: Int,
    val signalCooldownMillis: Long,
    val shadowThreshold: Double,
    val minimumIndependentFamilies: Int,
)

/** Per-check thresholds (enable flag, alert/setback VLs, decay), hot-reloadable. */
data class CheckConfig(
    val enabled: Boolean,
    val alertVl: Double,
    val setbackVl: Double,
    val decay: Double,
)

/** Staff alert formatting and throttling, hot-reloadable. */
data class AlertConfig(
    val enabled: Boolean,
    val message: String,
    val console: Boolean,
    val cooldownMillis: Long,
)

/** Per-signal confidence + noisy-OR caps, hot-reloadable so calibration needs no recompile. */
data class ConfidenceConfig(
    val signal: Map<String, Double>,
    val defaultSignal: Double,
    val singleFamilyCap: Double,
    val multiFamilyCap: Double,
    val deterministic: Double,
)

/** Discord webhook alerting, hot-reloadable. */
data class NotificationConfig(
    val discordEnabled: Boolean,
    val webhookUrl: String,
    val minConfidence: Double,
    val notifyBans: Boolean,
    val cooldownMillis: Long,
)

/** The hot-reloadable slice of the configuration (swapped atomically on `/iguard reload`). */
data class DynamicConfig(
    val checks: Map<String, CheckConfig>,
    val alerts: AlertConfig,
    val confidence: ConfidenceConfig,
    val notifications: NotificationConfig,
)

/** Enforcement policy (static: infra changes need a restart). */
data class SanctionConfig(
    val mode: String,
    val calibratedRecipe: String,
    val firstBanHours: Int,
    val repeatBanHours: Int,
)

/** Ban backend selection (static). Command placeholders: %player% %uuid% %reason% %hours% %actor%. */
data class BansConfig(
    val provider: String,
    val banCommand: String,
    val tempbanCommand: String,
    val unbanCommand: String,
)

/**
 * The full static IGuard configuration. Persistence always goes through a Helix-Cloud node
 * ([de.tytoss.iguard.storage.HelixNodeStore]); the node addon renders this config from its registry.
 */
data class IGuardConfig(
    val serverId: String,
    val workers: WorkerConfig,
    val history: HistoryConfig,
    val exemptions: ExemptionConfig,
    val sampler: SamplerConfig,
    val detection: DetectionConfig,
    val sanctions: SanctionConfig,
    val bans: BansConfig,
    val dynamic: DynamicConfig,
) {
    companion object {
        val checkIds = setOf(
            "client.identity.a",
            "client.brand_spoof.a",
            "protocol.badpackets.a",
            "movement.fly.a",
            "movement.speed.a",
            "movement.nofall.a",
            "movement.timer.a",
            "movement.phase.a",
            "movement.step.a",
            "movement.spider.a",
            "movement.jesus.a",
            "movement.velocity.a",
            "combat.reach.a",
            "combat.rotation.a",
            "combat.multitarget.a",
            "combat.autoclicker.a",
            "combat.inventory.a",
            "world.interactionreach.a",
            "world.scaffold.a",
            "world.fastplace.a",
            "world.fastbreak.a",
            "world.nuker.a",
            "world.nofacing.a",
            "inventory.impossible.a",
            "inventory.move.a",
            "movement.airjump.a",
            "movement.sprintbackwards.a",
            "combat.noswing.a",
            "combat.snapaim.a",
            "movement.fastladder.a",
            "movement.highjump.a",
            "movement.elytrafly.a",
        )

        // Fallback per-signal confidence (0..1) when confidence.signal.<id> is not set in config.
        val defaultSignalConfidence = mapOf(
            "client.brand_spoof.a" to 0.25,
            "client.identity.a" to 0.65,
            "protocol.badpackets.a" to 0.74,
            "movement.timer.a" to 0.70,
            "movement.phase.a" to 0.72,
            "movement.velocity.a" to 0.65,
            "movement.fly.a" to 0.62,
            "movement.speed.a" to 0.58,
            "movement.nofall.a" to 0.55,
            "movement.airjump.a" to 0.68,
            "movement.sprintbackwards.a" to 0.55,
            "movement.fastladder.a" to 0.62,
            "movement.highjump.a" to 0.66,
            "movement.elytrafly.a" to 0.65,
            "combat.reach.a" to 0.60,
            "combat.rotation.a" to 0.52,
            "combat.multitarget.a" to 0.68,
            "combat.autoclicker.a" to 0.55,
            "combat.noswing.a" to 0.58,
            "combat.snapaim.a" to 0.60,
            "world.scaffold.a" to 0.60,
            "world.fastbreak.a" to 0.60,
            "world.nuker.a" to 0.60,
            "world.nofacing.a" to 0.58,
            "inventory.impossible.a" to 0.65,
        )

        /**
         * Loads the static configuration from the plugin's `config.yml`. Persistence always runs
         * through the Helix node, so the HELIX_CONTROL_URL and HELIX_CONTROL_TOKEN environment
         * variables must be present (the Helix wrapper provides them); anything else fails fast
         * with a clear error instead of starting half-configured.
         */
        fun load(plugin: JavaPlugin): IGuardConfig {
            plugin.saveDefaultConfig()
            val source = plugin.config
            val serverId = value(source, "server-id")
            require(System.getenv("HELIX_CONTROL_URL").isNullOrBlank().not()) {
                "IGuard persists through a Helix-Cloud node: the HELIX_CONTROL_URL environment variable is required"
            }
            require(System.getenv("HELIX_CONTROL_TOKEN").isNullOrBlank().not()) {
                "IGuard persists through a Helix-Cloud node: the HELIX_CONTROL_TOKEN environment variable is required"
            }
            val workers = WorkerConfig(
                source.getInt("workers.stripes", 8).coerceIn(1, 64),
                source.getInt("workers.queue-capacity", 4096).coerceIn(64, 65536),
            )
            val history = HistoryConfig(
                source.getInt("history.queue-capacity", 10000).coerceIn(100, 1000000),
                source.getInt("history.batch-size", 250).coerceIn(1, 1000),
                source.getLong("history.flush-millis", 250).coerceIn(25, 5000),
                source.getInt("history.retention-days", 30).coerceIn(1, 3650),
            )
            val exemptions = ExemptionConfig(
                source.getLong("exemptions.overload-millis", 2000),
                source.getLong("exemptions.teleport-millis", 1500),
                source.getLong("exemptions.velocity-millis", 1000),
                source.getLong("exemptions.respawn-millis", 2000),
                source.getLong("exemptions.world-change-millis", 2000),
                source.getDouble("exemptions.low-tps-threshold", 18.0),
                // Must be >= the sampler's worst-case per-player refresh interval
                // (ceil(players / sampler.max-players-per-tick) * 50ms) plus jitter, otherwise
                // budgeted sampling makes far-cursor players look permanently stale.
                source.getLong("exemptions.snapshot-max-age-millis", 300),
            )
            val sampler = SamplerConfig(
                source.getInt("sampler.max-players-per-tick", 200).coerceIn(1, 4096),
                source.getLong("sampler.max-nanos-per-tick", 2_000_000).coerceIn(100_000, 40_000_000),
            )
            val detection = DetectionConfig(
                source.getLong("detection.incident-gap-millis", 30000).coerceIn(5000, 300000),
                source.getLong("detection.replay-pre-millis", 10000).coerceIn(1000, 60000),
                source.getLong("detection.replay-post-millis", 5000).coerceIn(1000, 30000),
                source.getInt("detection.replay-retention-days", 7).coerceIn(1, 90),
                source.getInt("detection.replay-max-bytes", 524288).coerceIn(65536, 4194304),
                source.getLong("detection.signal-cooldown-millis", 500).coerceIn(50, 5000),
                source.getDouble("sanctions.shadow-threshold", 0.80).coerceIn(0.5, 0.99),
                source.getInt("sanctions.minimum-independent-families", 2).coerceIn(2, 5),
            )
            val sanctions = SanctionConfig(
                source.getString("sanctions.mode")?.trim()?.lowercase().takeIf { it == "enforce" } ?: "shadow",
                source.getString("sanctions.calibrated-recipe")?.trim().orEmpty(),
                source.getInt("sanctions.first-ban-hours", 24).coerceIn(1, 8760),
                source.getInt("sanctions.repeat-ban-hours", 168).coerceIn(1, 8760),
            )
            val bans = BansConfig(
                source.getString("bans.provider")?.trim()?.lowercase().takeIf { it in setOf("native", "command", "service") } ?: "native",
                source.getString("bans.command.ban")?.trim().orEmpty(),
                source.getString("bans.command.tempban")?.trim().orEmpty(),
                source.getString("bans.command.unban")?.trim().orEmpty(),
            )
            return IGuardConfig(serverId, workers, history, exemptions, sampler, detection, sanctions, bans, dynamic(source))
        }

        /** Re-reads config.yml and returns the hot-reloadable (dynamic) part only. */
        fun reloadDynamic(plugin: JavaPlugin): DynamicConfig {
            plugin.reloadConfig()
            return dynamic(plugin.config)
        }

        private fun dynamic(source: FileConfiguration): DynamicConfig {
            val checks = checkIds.associateWith { id ->
                CheckConfig(
                    source.getBoolean("checks.$id.enabled", true),
                    source.getDouble("checks.$id.alert-vl", 5.0).coerceAtLeast(0.0),
                    source.getDouble("checks.$id.setback-vl", -1.0),
                    source.getDouble("checks.$id.decay", 0.2).coerceIn(0.0, 10.0),
                )
            }
            val alerts = AlertConfig(
                source.getBoolean("alerts.enabled", true),
                source.getString("alerts.message") ?: "&8[&cIGuard&8] &f%player% &7failed &c%check% &8(&7VL %vl%&8)",
                source.getBoolean("alerts.console", true),
                source.getLong("alerts.cooldown-millis", 1000).coerceAtLeast(100),
            )
            val defaultSignal = source.getDouble("confidence.default-signal", 0.50).coerceIn(0.0, 0.99)
            val signal = checkIds.associateWith { id ->
                source.getDouble("confidence.signal.$id", defaultSignalConfidence[id] ?: defaultSignal).coerceIn(0.0, 0.99)
            }
            val confidence = ConfidenceConfig(
                signal,
                defaultSignal,
                source.getDouble("confidence.single-family-cap", 0.79).coerceIn(0.0, 0.99),
                source.getDouble("confidence.multi-family-cap", 0.95).coerceIn(0.0, 0.99),
                source.getDouble("confidence.deterministic", 0.85).coerceIn(0.0, 0.99),
            )
            val notifications = NotificationConfig(
                source.getBoolean("notifications.discord.enabled", false),
                source.getString("notifications.discord.webhook-url")?.trim().orEmpty(),
                source.getDouble("notifications.discord.min-confidence", 0.80).coerceIn(0.0, 1.0),
                source.getBoolean("notifications.discord.notify-bans", true),
                source.getLong("notifications.discord.cooldown-millis", 30_000).coerceAtLeast(0),
            )
            return DynamicConfig(checks, alerts, confidence, notifications)
        }

        private fun value(source: FileConfiguration, path: String): String {
            val raw = source.getString(path)?.trim().orEmpty()
            require(raw.isNotEmpty()) { "$path must not be empty" }
            return Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").replace(raw) { match ->
                System.getenv(match.groupValues[1])
                    ?: throw IllegalArgumentException("Environment variable ${match.groupValues[1]} is not set")
            }
        }
    }
}
