package de.tytoss.iguard.config

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

/**
 * Per-check thresholds, hot-reloadable.
 *
 * @property enabled whether failures count at all.
 * @property alertVl violation level from which staff are alerted and the check feeds incident confidence fully.
 * @property setbackVl violation level from which movement checks set the player back (`< 0` = never).
 * @property decayPerSecond how much the violation level drops per second while the check passes.
 */
data class CheckConfig(
    val enabled: Boolean,
    val alertVl: Double,
    val setbackVl: Double,
    val decayPerSecond: Double,
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

/**
 * `/iguard debug` output pacing, hot-reloadable.
 *
 * @property rateLimitMillis minimum gap between two debug lines of the same viewer, player and check.
 */
data class DebugConfig(val rateLimitMillis: Long)

/** The hot-reloadable slice of the configuration (swapped atomically on `/iguard reload`). */
data class DynamicConfig(
    val checks: Map<String, CheckConfig>,
    val alerts: AlertConfig,
    val confidence: ConfidenceConfig,
    val notifications: NotificationConfig,
    val debug: DebugConfig = DebugConfig(DEFAULT_DEBUG_RATE_LIMIT_MILLIS),
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
 * Trace recorder limits (static).
 *
 * @property maxBytes a recording stops itself once its file reaches this size.
 * @property maxSeconds a recording stops itself after this many seconds.
 * @property queueCapacity lines buffered per recording before frames are dropped (counted in the end line).
 */
data class TraceConfig(val maxBytes: Long, val maxSeconds: Long, val queueCapacity: Int)

/**
 * Read access to a configuration tree with typed defaults — implemented over Bukkit's
 * `FileConfiguration` in the plugin and over a plain map in tests and trace replays.
 */
interface ConfigSource {
    /** The string at [path], or null when unset. */
    fun string(path: String): String?

    /** The int at [path], or [default] when unset. */
    fun int(path: String, default: Int): Int

    /** The long at [path], or [default] when unset. */
    fun long(path: String, default: Long): Long

    /** The double at [path], or [default] when unset. */
    fun double(path: String, default: Double): Double

    /** The boolean at [path], or [default] when unset. */
    fun boolean(path: String, default: Boolean): Boolean
}

/**
 * [ConfigSource] over dotted-path values (`checks.movement.fly.a.alert-vl` → `3.0`).
 *
 * @property values dotted path to value; numbers and booleans may also be given as strings.
 */
class MapConfigSource(private val values: Map<String, Any?> = emptyMap()) : ConfigSource {
    override fun string(path: String): String? = values[path]?.toString()

    override fun int(path: String, default: Int): Int = number(path)?.toInt() ?: default

    override fun long(path: String, default: Long): Long = number(path)?.toLong() ?: default

    override fun double(path: String, default: Double): Double = number(path)?.toDouble() ?: default

    override fun boolean(path: String, default: Boolean): Boolean = when (val value = values[path]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull() ?: default
        else -> default
    }

    private fun number(path: String): Number? = when (val value = values[path]) {
        is Number -> value
        is String -> value.toDoubleOrNull()
        else -> null
    }
}

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
    val trace: TraceConfig = TraceConfig(DEFAULT_TRACE_MAX_BYTES, DEFAULT_TRACE_MAX_SECONDS, DEFAULT_TRACE_QUEUE),
) {
    companion object {
        /**
         * Built-in per-check defaults, identical to the bundled config.yml and the node registry.
         * Decay is per second; the defaults keep the former per-packet tuning at the usual
         * evaluation rate (movement 20 frames/s, combat/world/inventory about 10/s).
         */
        val checkDefaults: Map<String, CheckConfig> = linkedMapOf(
            "client.identity.a" to CheckConfig(true, 1.0, -1.0, 0.0),
            "client.brand_spoof.a" to CheckConfig(true, 1.0, -1.0, 0.0),
            "protocol.badpackets.a" to CheckConfig(true, 1.0, -1.0, 0.0),
            "movement.fly.a" to CheckConfig(true, 4.0, 7.0, 4.0),
            "movement.speed.a" to CheckConfig(true, 5.0, 9.0, 4.0),
            "movement.nofall.a" to CheckConfig(true, 4.0, -1.0, 5.0),
            "movement.timer.a" to CheckConfig(true, 3.0, -1.0, 2.0),
            "movement.phase.a" to CheckConfig(true, 3.0, -1.0, 3.0),
            "movement.step.a" to CheckConfig(true, 3.0, -1.0, 4.0),
            "movement.spider.a" to CheckConfig(true, 3.0, -1.0, 4.0),
            "movement.jesus.a" to CheckConfig(true, 4.0, -1.0, 4.0),
            "movement.velocity.a" to CheckConfig(true, 4.0, -1.0, 1.0),
            "combat.reach.a" to CheckConfig(true, 4.0, -1.0, 2.5),
            "combat.rotation.a" to CheckConfig(true, 5.0, -1.0, 2.5),
            "combat.multitarget.a" to CheckConfig(true, 3.0, -1.0, 2.5),
            "combat.autoclicker.a" to CheckConfig(true, 5.0, -1.0, 1.0),
            "combat.inventory.a" to CheckConfig(true, 3.0, -1.0, 2.0),
            "world.interactionreach.a" to CheckConfig(true, 3.0, -1.0, 2.0),
            "world.scaffold.a" to CheckConfig(true, 4.0, -1.0, 2.0),
            "world.fastplace.a" to CheckConfig(true, 4.0, -1.0, 2.0),
            "world.fastbreak.a" to CheckConfig(true, 4.0, -1.0, 2.0),
            "world.nuker.a" to CheckConfig(true, 4.0, -1.0, 2.0),
            "world.nofacing.a" to CheckConfig(true, 4.0, -1.0, 2.0),
            "inventory.impossible.a" to CheckConfig(true, 1.0, -1.0, 0.0),
            "inventory.move.a" to CheckConfig(true, 4.0, -1.0, 2.0),
            "movement.airjump.a" to CheckConfig(true, 4.0, -1.0, 4.0),
            "movement.sprintbackwards.a" to CheckConfig(true, 5.0, -1.0, 4.0),
            "combat.noswing.a" to CheckConfig(true, 5.0, -1.0, 2.0),
            "combat.snapaim.a" to CheckConfig(true, 4.0, -1.0, 2.5),
            "movement.fastladder.a" to CheckConfig(true, 5.0, -1.0, 4.0),
            "movement.highjump.a" to CheckConfig(true, 4.0, -1.0, 4.0),
            "movement.elytrafly.a" to CheckConfig(true, 4.0, -1.0, 4.0),
        )

        /** Every check id IGuard knows. */
        val checkIds: Set<String> = checkDefaults.keys

        /** Fallback per-signal confidence (0..1) when `confidence.signal.<id>` is not set in config. */
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
         * Parses the static configuration (server id already resolved by the caller).
         *
         * @param source configuration values.
         * @param serverId this service's id.
         * @return the parsed configuration with every default applied.
         */
        fun parse(source: ConfigSource, serverId: String): IGuardConfig {
            val workers = WorkerConfig(
                source.int("workers.stripes", 8).coerceIn(1, 64),
                source.int("workers.queue-capacity", 4096).coerceIn(64, 65536),
            )
            val history = HistoryConfig(
                source.int("history.queue-capacity", 10000).coerceIn(100, 1000000),
                source.int("history.batch-size", 250).coerceIn(1, 1000),
                source.long("history.flush-millis", 250).coerceIn(25, 5000),
                source.int("history.retention-days", 30).coerceIn(1, 3650),
            )
            val exemptions = ExemptionConfig(
                source.long("exemptions.overload-millis", 2000),
                source.long("exemptions.teleport-millis", 1500),
                source.long("exemptions.velocity-millis", 1000),
                source.long("exemptions.respawn-millis", 2000),
                source.long("exemptions.world-change-millis", 2000),
                source.double("exemptions.low-tps-threshold", 18.0),
                // Must be >= the sampler's worst-case per-player refresh interval
                // (ceil(players / sampler.max-players-per-tick) * 50ms) plus jitter, otherwise
                // budgeted sampling makes far-cursor players look permanently stale.
                source.long("exemptions.snapshot-max-age-millis", 300),
            )
            val sampler = SamplerConfig(
                source.int("sampler.max-players-per-tick", 200).coerceIn(1, 4096),
                source.long("sampler.max-nanos-per-tick", 2_000_000).coerceIn(100_000, 40_000_000),
            )
            val detection = DetectionConfig(
                source.long("detection.incident-gap-millis", 30000).coerceIn(5000, 300000),
                source.long("detection.replay-pre-millis", 10000).coerceIn(1000, 60000),
                source.long("detection.replay-post-millis", 5000).coerceIn(1000, 30000),
                source.int("detection.replay-retention-days", 7).coerceIn(1, 90),
                source.int("detection.replay-max-bytes", 524288).coerceIn(65536, 4194304),
                source.long("detection.signal-cooldown-millis", 500).coerceIn(50, 5000),
                source.double("sanctions.shadow-threshold", 0.80).coerceIn(0.5, 0.99),
                source.int("sanctions.minimum-independent-families", 2).coerceIn(2, 5),
            )
            val sanctions = SanctionConfig(
                source.string("sanctions.mode")?.trim()?.lowercase().takeIf { it == "enforce" } ?: "shadow",
                source.string("sanctions.calibrated-recipe")?.trim().orEmpty(),
                source.int("sanctions.first-ban-hours", 24).coerceIn(1, 8760),
                source.int("sanctions.repeat-ban-hours", 168).coerceIn(1, 8760),
            )
            val bans = BansConfig(
                source.string("bans.provider")?.trim()?.lowercase().takeIf { it in setOf("native", "command", "service") } ?: "native",
                source.string("bans.command.ban")?.trim().orEmpty(),
                source.string("bans.command.tempban")?.trim().orEmpty(),
                source.string("bans.command.unban")?.trim().orEmpty(),
            )
            val trace = TraceConfig(
                source.long("trace.max-bytes", DEFAULT_TRACE_MAX_BYTES).coerceIn(1L shl 20, 1L shl 32),
                source.long("trace.max-seconds", DEFAULT_TRACE_MAX_SECONDS).coerceIn(10, 86_400),
                source.int("trace.queue-capacity", DEFAULT_TRACE_QUEUE).coerceIn(1_000, 1_000_000),
            )
            return IGuardConfig(serverId, workers, history, exemptions, sampler, detection, sanctions, bans, dynamic(source), trace)
        }

        /**
         * Parses the hot-reloadable part of the configuration.
         *
         * @param source configuration values.
         * @return the dynamic configuration with every default applied.
         */
        fun dynamic(source: ConfigSource): DynamicConfig {
            val checks = checkDefaults.mapValues { (id, fallback) ->
                CheckConfig(
                    source.boolean("checks.$id.enabled", fallback.enabled),
                    source.double("checks.$id.alert-vl", fallback.alertVl).coerceAtLeast(0.0),
                    source.double("checks.$id.setback-vl", fallback.setbackVl),
                    source.double("checks.$id.decay-per-second", fallback.decayPerSecond).coerceIn(0.0, 100.0),
                )
            }
            val alerts = AlertConfig(
                source.boolean("alerts.enabled", true),
                source.string("alerts.message") ?: "&8[&cIGuard&8] &f%player% &7failed &c%check% &8(&7VL %vl%&8)",
                source.boolean("alerts.console", true),
                source.long("alerts.cooldown-millis", 1000).coerceAtLeast(100),
            )
            val defaultSignal = source.double("confidence.default-signal", 0.50).coerceIn(0.0, 0.99)
            val signal = checkIds.associateWith { id ->
                source.double("confidence.signal.$id", defaultSignalConfidence[id] ?: defaultSignal).coerceIn(0.0, 0.99)
            }
            val confidence = ConfidenceConfig(
                signal,
                defaultSignal,
                source.double("confidence.single-family-cap", 0.79).coerceIn(0.0, 0.99),
                source.double("confidence.multi-family-cap", 0.95).coerceIn(0.0, 0.99),
                source.double("confidence.deterministic", 0.85).coerceIn(0.0, 0.99),
            )
            val notifications = NotificationConfig(
                source.boolean("notifications.discord.enabled", false),
                source.string("notifications.discord.webhook-url")?.trim().orEmpty(),
                source.double("notifications.discord.min-confidence", 0.80).coerceIn(0.0, 1.0),
                source.boolean("notifications.discord.notify-bans", true),
                source.long("notifications.discord.cooldown-millis", 30_000).coerceAtLeast(0),
            )
            val debug = DebugConfig(source.long("debug.rate-limit-millis", DEFAULT_DEBUG_RATE_LIMIT_MILLIS).coerceIn(0, 60_000))
            return DynamicConfig(checks, alerts, confidence, notifications, debug)
        }

        /**
         * The configuration with every built-in default (trace replays, tests).
         *
         * @param serverId server id to report.
         * @return the default configuration.
         */
        fun defaults(serverId: String = "local"): IGuardConfig = parse(MapConfigSource(), serverId)
    }
}

/** Default `debug.rate-limit-millis`. */
const val DEFAULT_DEBUG_RATE_LIMIT_MILLIS: Long = 200L

/** Default `trace.max-bytes` (64 MiB). */
const val DEFAULT_TRACE_MAX_BYTES: Long = 64L * 1024 * 1024

/** Default `trace.max-seconds` (15 minutes). */
const val DEFAULT_TRACE_MAX_SECONDS: Long = 900L

/** Default `trace.queue-capacity`. */
const val DEFAULT_TRACE_QUEUE: Int = 50_000
