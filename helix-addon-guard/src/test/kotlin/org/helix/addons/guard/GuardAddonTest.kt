package org.helix.addons.guard

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.api.addon.AddonContext

/** [RecordingAddonContext] wrapper that additionally reports service directories. */
private class GuardTestContext(
    val recording: RecordingAddonContext,
    private val serviceRoots: List<Path>,
) : AddonContext by recording {
    override fun serviceDirectories(): List<Path> = serviceRoots
}

class GuardAddonTest {
    private val recording = RecordingAddonContext(createTempDirectory("guard"))
    private val staticRoot = createTempDirectory("guard-static")
    private val tempRoot = createTempDirectory("guard-temp")
    private val templatesRoot = createTempDirectory("guard-templates")
    private val context = GuardTestContext(recording, listOf(staticRoot, tempRoot, templatesRoot))
    private val addon = GuardAddon().also { it.onEnable(context) }
    private val json = Json

    private fun fakeServices(vararg lines: String) {
        recording.registerAction(ActionDescriptor("service.list", "fake", "service.list")) {
            ActionResult.ok(*lines)
        }
        recording.registerAction(
            ActionDescriptor("service.command", "fake", "service.command <service> <line...>"),
        ) { ActionResult.ok("sent") }
    }

    private fun effective(): Map<String, GuardSettingView> =
        json.decodeFromString<List<GuardSettingView>>(recording.run("guard.config.get").lines.single())
            .associateBy { it.path }

    @Test
    fun `registry mirrors the full iguard schema`() {
        // 55 plain settings + 31 checks x 4 fields; server-id is fixed and not editable
        assertEquals(55 + 31 * 4, GuardConfig.settings.size)
        assertEquals(GuardConfig.settings.size, GuardConfig.byPath.size)
        assertFalse("server-id" in GuardConfig.byPath)

        assertEquals(16, GuardConfig.settings.count { it.static })
        listOf(
            "database.host", "database.pool-size", "workers.stripes", "workers.queue-capacity",
            "history.queue-capacity", "history.batch-size", "history.flush-millis",
            "dashboard.enabled", "dashboard.bind", "dashboard.port", "dashboard.token",
        ).forEach { path -> assertTrue(GuardConfig.byPath.getValue(path).static, path) }
        listOf(
            "history.retention-days", "alerts.enabled", "alerts.message", "bans.provider",
            "bans.command.tempban", "notifications.discord.webhook-url",
            "exemptions.low-tps-threshold", "sampler.max-players-per-tick",
            "detection.incident-gap-millis", "sanctions.mode", "confidence.deterministic",
            "checks.movement.fly.a.alert-vl",
        ).forEach { path -> assertFalse(GuardConfig.byPath.getValue(path).static, path) }

        assertEquals("7.0", GuardConfig.byPath.getValue("checks.movement.fly.a.setback-vl").default)
        assertEquals(GuardValueType.DOUBLE, GuardConfig.byPath.getValue("checks.combat.reach.a.decay").type)
        assertEquals("0.80", GuardConfig.byPath.getValue("notifications.discord.min-confidence").default)
    }

    @Test
    fun `set and reset round-trip with type validation`() {
        fakeServices()
        val before = effective().getValue("alerts.cooldown-millis")
        assertEquals("1000", before.value)
        assertFalse(before.overridden)

        assertTrue(recording.run("guard.config.set", "alerts.cooldown-millis", "2500").success)
        val after = effective().getValue("alerts.cooldown-millis")
        assertEquals("2500", after.value)
        assertEquals("1000", after.default)
        assertTrue(after.overridden)

        // multi-word string values are joined again
        assertTrue(recording.run("guard.config.set", "alerts.message", "cheater", "%player%", "found").success)
        assertEquals("cheater %player% found", effective().getValue("alerts.message").value)

        // type validation and unknown/fixed paths
        assertFalse(recording.run("guard.config.set", "alerts.cooldown-millis", "soon").success)
        assertFalse(recording.run("guard.config.set", "alerts.enabled", "maybe").success)
        assertFalse(recording.run("guard.config.set", "confidence.deterministic", "high").success)
        assertFalse(recording.run("guard.config.set", "no.such.path", "1").success)
        assertFalse(recording.run("guard.config.set", "server-id", "paper-7").success)
        assertFalse(recording.run("guard.config.reset", "no.such.path").success)

        assertTrue(recording.run("guard.config.reset", "alerts.cooldown-millis").success)
        assertFalse(effective().getValue("alerts.cooldown-millis").overridden)
        assertTrue(recording.run("guard.config.reset", "all").success)
        assertTrue(effective().values.none { it.overridden })
    }

    @Test
    fun `overrides persist across store instances`() {
        val store = GuardConfigStore(recording.storage)
        store.set("alerts.enabled", "false")
        assertEquals(mapOf("alerts.enabled" to "false"), GuardConfigStore(recording.storage).overrides())
        assertTrue(GuardConfigStore(recording.storage).remove("alerts.enabled"))
        assertTrue(GuardConfigStore(recording.storage).overrides().isEmpty())
    }

    @Test
    fun `renderConfigYaml nests dotted paths with two-space indent`() {
        val yaml = GuardConfig.renderConfigYaml(
            mapOf("alerts.cooldown-millis" to "2500", "checks.movement.fly.a.enabled" to "false"),
        )
        val lines = yaml.lines()
        assertEquals("server-id: \"\${HELIX_SERVICE_ID}\"", lines.first())
        assertContains(lines, "database:")
        assertContains(lines, "  host: \"127.0.0.1\"")
        assertContains(lines, "  pool-size: 6")
        assertContains(lines, "  cooldown-millis: 2500")
        assertContains(lines, "bans:")
        assertContains(lines, "  command:")
        assertContains(lines, "    ban: \"ban %player% %reason%\"")
        assertContains(lines, "notifications:")
        assertContains(lines, "  discord:")
        assertContains(lines, "    min-confidence: 0.80")
        assertContains(lines, "checks:")

        // check ids keep their inner dots as a single YAML key
        val fly = lines.indexOf("  movement.fly.a:")
        assertTrue(fly > 0)
        assertEquals("    enabled: false", lines[fly + 1])
        assertEquals("    alert-vl: 4.0", lines[fly + 2])
        assertEquals("    setback-vl: 7.0", lines[fly + 3])
        assertEquals("    decay: 0.2", lines[fly + 4])
    }

    @Test
    fun `apply writes configs into every service subdirectory and reloads running services`() {
        Files.createDirectories(staticRoot.resolve("Lobby-1").resolve("plugins"))
        Files.createDirectories(tempRoot.resolve("Game-1"))
        Files.createDirectories(templatesRoot.resolve("lobby"))
        fakeServices(
            "Lobby-1 [RUNNING] port=30000 players=3/100 executor=PROCESS",
            "Game-1 [STARTING] port=30001 players=0/100 executor=PROCESS",
            "Game-2 [RUNNING] port=30002 players=0/100 executor=DOCKER",
        )

        val result = recording.run("guard.apply")
        assertTrue(result.success)
        assertTrue(result.lines.any { it.contains("3 service directories") }, result.lines.toString())

        listOf(
            staticRoot.resolve("Lobby-1/plugins/IGuard/config.yml"),
            tempRoot.resolve("Game-1/plugins/IGuard/config.yml"),
            templatesRoot.resolve("lobby/plugins/IGuard/config.yml"),
        ).forEach { path ->
            assertTrue(Files.isRegularFile(path), path.toString())
            assertTrue(Files.readString(path).startsWith("server-id: \"\${HELIX_SERVICE_ID}\""))
        }

        val reloads = recording.invocations.filter { it.action == "service.command" }
        assertEquals(
            listOf(
                listOf("Lobby-1", "iguard", "reload"),
                listOf("Game-2", "iguard", "reload"),
            ),
            reloads.map { it.arguments },
        )
    }

    @Test
    fun `changing a static value warns about the required restart`() {
        fakeServices()
        val static = recording.run("guard.config.set", "database.pool-size", "10")
        assertTrue(static.success)
        assertTrue(
            static.lines.any { it.contains("requires service restart") && it.contains("database.pool-size") },
            static.lines.toString(),
        )

        val dynamic = recording.run("guard.config.set", "alerts.enabled", "false")
        assertTrue(dynamic.success)
        assertFalse(dynamic.lines.any { it.contains("requires service restart") })

        val reset = recording.run("guard.config.reset", "all")
        assertTrue(reset.success)
        assertTrue(
            reset.lines.any { it.contains("requires service restart") && it.contains("database.pool-size") },
            reset.lines.toString(),
        )
    }
}
