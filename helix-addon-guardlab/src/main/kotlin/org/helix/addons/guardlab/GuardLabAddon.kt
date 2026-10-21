package org.helix.addons.guardlab

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionResult
import java.time.Clock
import java.util.UUID

/**
 * Node side of GuardLab, the automated IGuard test lab.
 *
 * The Paper component (`helix-addon-guardlab-paper`, active only on the task [TEST_TASK]) runs scenario
 * suites against IGuard, driven by the GuardLab dev client. This addon keeps the lab settings (the shared
 * client token and the default suite), publishes them to the test task as the bridge value [SETTINGS_KEY],
 * accepts run requests ([REQUEST_KEY]) and stores the summaries of finished runs.
 *
 * @property clock time source (tests inject a fixed clock).
 */
class GuardLabAddon(private val clock: Clock = Clock.systemUTC()) : AddonBase() {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    /** Registers the lab actions and the panel and publishes the current settings. */
    override fun enable() {
        publishSettings(settings())
        action(
            "guardlab.configure",
            "Sets the GuardLab client token, the default suite and whether runs start automatically.",
            "guardlab.configure <token|-> [suite] [autostart=true|false]",
            permission = ADMIN,
        ) { inv -> configure(inv.arguments) }
        action("guardlab.status", "Shows the GuardLab settings (token masked) and the latest run.", "guardlab.status") {
            val s = settings()
            val latest = reports().firstOrNull()
            ActionResult.ok(
                "token: ${if (s.token.isBlank()) "(none)" else "set (${s.token.length} chars)"}",
                "suite: ${s.suite}, autostart: ${s.autostart}",
                "latest: " + (latest?.let { "${it.suite} ${it.passed} passed (${it.exempt} exempt), ${it.warned} warned, ${it.failed} failed, ${it.skipped} skipped (${it.finishedAt})" } ?: "none"),
            )
        }
        action(
            "guardlab.run",
            "Asks the test task to run a suite for a connected GuardLab client.",
            "guardlab.run <suite> [player]",
            permission = ADMIN,
        ) { inv ->
            val suite = inv.arguments.getOrNull(0)?.takeIf { SUITE_PATTERN.matches(it) }
                ?: return@action ActionResult.error("usage: guardlab.run <suite> [player]")
            val player = inv.arguments.getOrNull(1).orEmpty()
            val request = RunRequest(suite, player, UUID.randomUUID().toString(), clock.millis())
            runCatching { context.publishBridgeValue(REQUEST_KEY, json.encodeToString(RunRequest.serializer(), request)) }
            ActionResult.ok("run requested: $suite${if (player.isNotBlank()) " for $player" else ""} (${request.nonce})")
        }
        action(
            "guardlab.report.store",
            "Stores the summary of a finished GuardLab run (Paper component).",
            "guardlab.report.store <json>",
            bridgeInvocable = true,
            bridgeTasks = setOf(TEST_TASK),
        ) { inv -> store(inv.arguments.joinToString(" ")) }
        action("guardlab.reports", "Lists the latest GuardLab run summaries (newest first).", "guardlab.reports [limit]") { inv ->
            val limit = inv.arguments.getOrNull(0)?.toIntOrNull()?.coerceIn(1, MAX_REPORTS) ?: 20
            ActionResult.ok(json.encodeToString(ListSerializer(RunSummary.serializer()), reports().take(limit)))
        }
        panel("guardlab", "GuardLab", "/panel.html")
    }

    private fun configure(args: List<String>): ActionResult {
        val tokenArg = args.getOrNull(0) ?: return ActionResult.error("usage: guardlab.configure <token|-> [suite] [autostart=true|false]")
        val current = settings()
        val token = when (tokenArg) {
            "-" -> current.token
            else -> tokenArg.takeIf { TOKEN_PATTERN.matches(it) } ?: return ActionResult.error("invalid token: 16..128 chars [A-Za-z0-9_-]")
        }
        val suite = args.getOrNull(1)?.let { it.takeIf { s -> SUITE_PATTERN.matches(s) } ?: return ActionResult.error("invalid suite: $it") }
            ?: current.suite
        val autostart = args.getOrNull(2)?.let {
            it.toBooleanStrictOrNull() ?: return ActionResult.error("autostart must be true or false")
        } ?: current.autostart
        val updated = LabSettings(token, suite, autostart)
        synchronized(lock) { context.storage().write(SETTINGS_DOC, json.encodeToString(LabSettings.serializer(), updated)) }
        publishSettings(updated)
        return ActionResult.ok("guardlab configured: suite $suite, autostart $autostart")
    }

    private fun store(raw: String): ActionResult {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return ActionResult.error("invalid json")
        val summary = runCatching { json.decodeFromJsonElement(RunSummary.serializer(), obj) }.getOrNull()
            ?: return ActionResult.error("invalid summary")
        if (!SUITE_PATTERN.matches(summary.suite)) return ActionResult.error("invalid suite: ${summary.suite}")
        synchronized(lock) {
            val list = (listOf(summary) + reports()).take(MAX_REPORTS)
            context.storage().write(REPORTS_DOC, json.encodeToString(ListSerializer(RunSummary.serializer()), list))
        }
        return ActionResult.ok("stored")
    }

    private fun settings(): LabSettings =
        context.storage().read(SETTINGS_DOC)?.let { runCatching { json.decodeFromString(LabSettings.serializer(), it) }.getOrNull() }
            ?: LabSettings()

    private fun reports(): List<RunSummary> =
        context.storage().read(REPORTS_DOC)?.let {
            runCatching { json.decodeFromString(ListSerializer(RunSummary.serializer()), it) }.getOrNull()
        } ?: emptyList()

    private fun publishSettings(settings: LabSettings) {
        val value: JsonObject = buildJsonObject {
            put("token", settings.token)
            put("suite", settings.suite)
            put("autostart", settings.autostart)
        }
        runCatching { context.publishBridgeValue(SETTINGS_KEY, value.toString()) }
    }

    /** Constants of the lab. */
    companion object {
        /** Admin permission for configuring and starting runs. */
        const val ADMIN = "guardlab.admin"

        /** The only task the Paper component is active on. */
        const val TEST_TASK = "IGuard-Test"

        /** Bridge value with the lab settings (`{token, suite, autostart}`). */
        const val SETTINGS_KEY = "guardlab.settings"

        /** Bridge value with the latest run request (`{suite, player, nonce, at}`). */
        const val REQUEST_KEY = "guardlab.request"

        private const val SETTINGS_DOC = "settings"
        private const val REPORTS_DOC = "reports"
        private const val MAX_REPORTS = 50
        private val SUITE_PATTERN = Regex("^[a-z0-9_-]{1,32}$")
        private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{16,128}$")
    }
}

/**
 * Persisted lab settings.
 *
 * @property token shared secret the GuardLab client must present (empty = lab closed).
 * @property suite suite run when a client becomes ready.
 * @property autostart whether a ready client starts [suite] automatically.
 */
@Serializable
data class LabSettings(val token: String = "", val suite: String = "all", val autostart: Boolean = true)

/**
 * A run request published to the test task.
 *
 * @property suite suite to run.
 * @property player target client name (empty = the first ready client).
 * @property nonce unique id, so the Paper component runs each request once.
 * @property at request time (epoch millis).
 */
@Serializable
data class RunRequest(val suite: String, val player: String = "", val nonce: String, val at: Long)

/**
 * Summary of a finished run, as stored and listed.
 *
 * @property suite suite id.
 * @property player client name.
 * @property startedAt start (ISO-8601).
 * @property finishedAt end (ISO-8601).
 * @property passed scenarios passed.
 * @property warned scenarios passed with sub-threshold noise.
 * @property failed scenarios failed.
 * @property skipped scenarios skipped (missing dummy client, unsupported cheat mode …).
 * @property report report path relative to the test server workspace.
 * @property service the test service id.
 * @property exempt passed scenarios IGuard hardly checked (`pass (exempt)`, part of [passed]).
 */
@Serializable
data class RunSummary(
    val suite: String,
    val player: String = "",
    val startedAt: String = "",
    val finishedAt: String = "",
    val passed: Int = 0,
    val warned: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val report: String = "",
    val service: String = "",
    val exempt: Int = 0,
)
