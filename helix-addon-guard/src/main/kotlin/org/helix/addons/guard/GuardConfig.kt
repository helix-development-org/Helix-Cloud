package org.helix.addons.guard

/**
 * Registry of every IGuard config value plus the YAML renderer.
 *
 * The registry mirrors IGuard's bundled `config.yml` one to one: every
 * setting carries its dotted path, type, default and whether a change
 * requires a service restart (`static`) or is hot-reloadable via
 * `iguard reload` (dynamic).
 *
 * The only value that is not editable is `server-id`: it is always
 * rendered as the literal `${HELIX_SERVICE_ID}`, which IGuard substitutes
 * from the service's environment, so every service reports under its own
 * Helix service id.
 */
object GuardConfig {
    /** Fixed value rendered for `server-id`; resolved by IGuard from the service environment. */
    const val SERVER_ID_VALUE: String = "\${HELIX_SERVICE_ID}"

    /** Fixed value rendered for `storage.mode`; IGuard persists through the node. */
    const val STORAGE_MODE_VALUE: String = "helix"

    /** Per-check default values copied from IGuard's bundled config.yml. */
    private data class CheckDefaults(
        val id: String,
        val alertVl: String,
        val setbackVl: String,
        val decay: String,
    )

    private val checkDefaults = listOf(
        CheckDefaults("client.identity.a", "1.0", "-1.0", "0.0"),
        CheckDefaults("client.brand_spoof.a", "1.0", "-1.0", "0.0"),
        CheckDefaults("protocol.badpackets.a", "1.0", "-1.0", "0.0"),
        CheckDefaults("movement.fly.a", "4.0", "7.0", "0.2"),
        CheckDefaults("movement.speed.a", "5.0", "9.0", "0.2"),
        CheckDefaults("movement.nofall.a", "4.0", "-1.0", "0.25"),
        CheckDefaults("movement.timer.a", "3.0", "-1.0", "0.1"),
        CheckDefaults("movement.phase.a", "3.0", "-1.0", "0.15"),
        CheckDefaults("movement.step.a", "3.0", "-1.0", "0.2"),
        CheckDefaults("movement.spider.a", "3.0", "-1.0", "0.2"),
        CheckDefaults("movement.jesus.a", "4.0", "-1.0", "0.2"),
        CheckDefaults("movement.velocity.a", "4.0", "-1.0", "0.1"),
        CheckDefaults("combat.reach.a", "4.0", "-1.0", "0.25"),
        CheckDefaults("combat.rotation.a", "5.0", "-1.0", "0.25"),
        CheckDefaults("combat.multitarget.a", "3.0", "-1.0", "0.25"),
        CheckDefaults("combat.autoclicker.a", "5.0", "-1.0", "0.1"),
        CheckDefaults("combat.inventory.a", "3.0", "-1.0", "0.2"),
        CheckDefaults("world.interactionreach.a", "3.0", "-1.0", "0.2"),
        CheckDefaults("world.scaffold.a", "4.0", "-1.0", "0.2"),
        CheckDefaults("world.fastplace.a", "4.0", "-1.0", "0.2"),
        CheckDefaults("world.fastbreak.a", "4.0", "-1.0", "0.2"),
        CheckDefaults("world.nuker.a", "4.0", "-1.0", "0.2"),
        CheckDefaults("inventory.impossible.a", "1.0", "-1.0", "0.0"),
        CheckDefaults("inventory.move.a", "4.0", "-1.0", "0.2"),
        CheckDefaults("movement.airjump.a", "4.0", "-1.0", "0.2"),
        CheckDefaults("movement.sprintbackwards.a", "5.0", "-1.0", "0.2"),
        CheckDefaults("combat.noswing.a", "5.0", "-1.0", "0.2"),
        CheckDefaults("combat.snapaim.a", "4.0", "-1.0", "0.25"),
        CheckDefaults("world.nofacing.a", "4.0", "-1.0", "0.2"),
        CheckDefaults("movement.fastladder.a", "5.0", "-1.0", "0.2"),
        CheckDefaults("movement.highjump.a", "4.0", "-1.0", "0.2"),
    )

    /** All editable settings in the order of IGuard's bundled config.yml. */
    val settings: List<GuardSetting> = baseSettings() + checkSettings()

    /** Settings indexed by dotted path. */
    val byPath: Map<String, GuardSetting> = settings.associateBy { it.path }

    /**
     * Renders a complete IGuard `config.yml` from defaults and overrides.
     *
     * Dotted paths are grouped back into nested YAML with 2-space indent;
     * strings are double-quoted, booleans and numbers stay bare. The first
     * line is always the fixed `server-id` entry, followed by the fixed
     * `storage.mode: "helix"` block — IGuard persists through the node
     * (`guard.store.*` actions) instead of its own database connection.
     *
     * @param overrides map of dotted path to canonical override value.
     * @return the full config.yml content, ready to write to disk.
     */
    fun renderConfigYaml(overrides: Map<String, String>): String {
        val root = LinkedHashMap<String, Any>()
        root["server-id"] = quote(SERVER_ID_VALUE)
        val storageNode = LinkedHashMap<String, Any>()
        storageNode["mode"] = quote(STORAGE_MODE_VALUE)
        root["storage"] = storageNode
        settings.forEach { setting ->
            var node = root
            setting.segments.dropLast(1).forEach { segment ->
                @Suppress("UNCHECKED_CAST")
                node = node.getOrPut(segment) { LinkedHashMap<String, Any>() } as LinkedHashMap<String, Any>
            }
            val value = overrides[setting.path] ?: setting.default
            node[setting.segments.last()] =
                if (setting.type == GuardValueType.STRING) quote(value) else value
        }
        return buildString { writeNode(this, root, 0) }
    }

    private fun writeNode(out: StringBuilder, node: Map<String, Any>, indent: Int) {
        node.forEach { (key, value) ->
            repeat(indent) { out.append("  ") }
            out.append(key)
            if (value is Map<*, *>) {
                out.append(":\n")
                @Suppress("UNCHECKED_CAST")
                writeNode(out, value as Map<String, Any>, indent + 1)
            } else {
                out.append(": ").append(value).append('\n')
            }
        }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun plain(
        path: String,
        type: GuardValueType,
        default: String,
        static: Boolean = false,
    ): GuardSetting = GuardSetting(path, path.split('.'), type, default, static)

    private fun baseSettings(): List<GuardSetting> = listOf(
        // workers.* — thread pool sized on startup
        plain("workers.stripes", GuardValueType.INT, "8", static = true),
        plain("workers.queue-capacity", GuardValueType.INT, "4096", static = true),
        // history.* — writer queue is static, retention is dynamic
        plain("history.queue-capacity", GuardValueType.INT, "10000", static = true),
        plain("history.batch-size", GuardValueType.INT, "250", static = true),
        plain("history.flush-millis", GuardValueType.INT, "250", static = true),
        plain("history.retention-days", GuardValueType.INT, "30"),
        // alerts.*
        plain("alerts.enabled", GuardValueType.BOOLEAN, "true"),
        plain(
            "alerts.message",
            GuardValueType.STRING,
            "&8[&cIGuard&8] &f%player% &7failed &c%check% &8(&7VL %vl%&8) " +
                "&7case=%incident% confidence=%confidence%% shadow=%shadow% %details%",
        ),
        plain("alerts.console", GuardValueType.BOOLEAN, "true"),
        plain("alerts.cooldown-millis", GuardValueType.INT, "1000"),
        // bans.* including command templates
        plain("bans.provider", GuardValueType.STRING, "native"),
        plain("bans.command.ban", GuardValueType.STRING, "ban %player% %reason%"),
        plain("bans.command.tempban", GuardValueType.STRING, "tempban %player% %hours%h %reason%"),
        plain("bans.command.unban", GuardValueType.STRING, "pardon %player%"),
        // dashboard.* — embedded web server bound on startup
        plain("dashboard.enabled", GuardValueType.BOOLEAN, "false", static = true),
        plain("dashboard.bind", GuardValueType.STRING, "0.0.0.0", static = true),
        plain("dashboard.port", GuardValueType.INT, "8085", static = true),
        plain("dashboard.token", GuardValueType.STRING, "", static = true),
        // notifications.discord.*
        plain("notifications.discord.enabled", GuardValueType.BOOLEAN, "false"),
        plain("notifications.discord.webhook-url", GuardValueType.STRING, ""),
        plain("notifications.discord.min-confidence", GuardValueType.DOUBLE, "0.80"),
        plain("notifications.discord.notify-bans", GuardValueType.BOOLEAN, "true"),
        plain("notifications.discord.cooldown-millis", GuardValueType.INT, "30000"),
        // exemptions.*
        plain("exemptions.overload-millis", GuardValueType.INT, "2000"),
        plain("exemptions.teleport-millis", GuardValueType.INT, "1500"),
        plain("exemptions.velocity-millis", GuardValueType.INT, "1000"),
        plain("exemptions.respawn-millis", GuardValueType.INT, "2000"),
        plain("exemptions.world-change-millis", GuardValueType.INT, "2000"),
        plain("exemptions.low-tps-threshold", GuardValueType.DOUBLE, "18.0"),
        plain("exemptions.snapshot-max-age-millis", GuardValueType.INT, "300"),
        // sampler.*
        plain("sampler.max-players-per-tick", GuardValueType.INT, "256"),
        plain("sampler.max-nanos-per-tick", GuardValueType.INT, "2000000"),
        // detection.*
        plain("detection.incident-gap-millis", GuardValueType.INT, "30000"),
        plain("detection.replay-pre-millis", GuardValueType.INT, "10000"),
        plain("detection.replay-post-millis", GuardValueType.INT, "5000"),
        plain("detection.replay-retention-days", GuardValueType.INT, "7"),
        plain("detection.replay-max-bytes", GuardValueType.INT, "524288"),
        plain("detection.signal-cooldown-millis", GuardValueType.INT, "500"),
        // sanctions.*
        plain("sanctions.mode", GuardValueType.STRING, "shadow"),
        plain("sanctions.calibrated-recipe", GuardValueType.STRING, ""),
        plain("sanctions.shadow-threshold", GuardValueType.DOUBLE, "0.80"),
        plain("sanctions.minimum-independent-families", GuardValueType.INT, "2"),
        plain("sanctions.first-ban-hours", GuardValueType.INT, "24"),
        plain("sanctions.repeat-ban-hours", GuardValueType.INT, "168"),
        // confidence.*
        plain("confidence.default-signal", GuardValueType.DOUBLE, "0.50"),
        plain("confidence.single-family-cap", GuardValueType.DOUBLE, "0.79"),
        plain("confidence.multi-family-cap", GuardValueType.DOUBLE, "0.95"),
        plain("confidence.deterministic", GuardValueType.DOUBLE, "0.85"),
    )

    private fun checkSettings(): List<GuardSetting> = checkDefaults.flatMap { check ->
        listOf(
            GuardSetting(
                "checks.${check.id}.enabled",
                listOf("checks", check.id, "enabled"),
                GuardValueType.BOOLEAN,
                "true",
                false,
            ),
            GuardSetting(
                "checks.${check.id}.alert-vl",
                listOf("checks", check.id, "alert-vl"),
                GuardValueType.DOUBLE,
                check.alertVl,
                false,
            ),
            GuardSetting(
                "checks.${check.id}.setback-vl",
                listOf("checks", check.id, "setback-vl"),
                GuardValueType.DOUBLE,
                check.setbackVl,
                false,
            ),
            GuardSetting(
                "checks.${check.id}.decay",
                listOf("checks", check.id, "decay"),
                GuardValueType.DOUBLE,
                check.decay,
                false,
            ),
        )
    }
}
