package org.helix.addons.guard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.helix.addon.sdk.testing.RecordingAddonContext
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.api.addon.AddonContext
import org.helix.api.player.OnlinePlayer
import org.helix.api.proxy.JoinRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        // 44 plain settings + 32 checks x 4 fields; server-id and storage.mode
        // are fixed and not editable
        assertEquals(44 + 32 * 4, GuardConfig.settings.size)
        assertEquals(GuardConfig.settings.size, GuardConfig.byPath.size)
        assertFalse("server-id" in GuardConfig.byPath)
        assertFalse("storage.mode" in GuardConfig.byPath)
        assertFalse("database.pool-size" in GuardConfig.byPath)

        assertEquals(5, GuardConfig.settings.count { it.static })
        listOf(
            "workers.stripes", "workers.queue-capacity",
            "history.queue-capacity", "history.batch-size", "history.flush-millis",
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
        // IGuard persists through the node — fixed storage block, no database section
        assertContains(lines, "storage:")
        assertContains(lines, "  mode: \"helix\"")
        assertFalse(lines.contains("database:"))
        assertFalse(lines.any { it.contains("pool-size") })
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
        val static = recording.run("guard.config.set", "workers.stripes", "16")
        assertTrue(static.success)
        assertTrue(
            static.lines.any { it.contains("requires service restart") && it.contains("workers.stripes") },
            static.lines.toString(),
        )

        val dynamic = recording.run("guard.config.set", "alerts.enabled", "false")
        assertTrue(dynamic.success)
        assertFalse(dynamic.lines.any { it.contains("requires service restart") })

        val reset = recording.run("guard.config.reset", "all")
        assertTrue(reset.success)
        assertTrue(
            reset.lines.any { it.contains("requires service restart") && it.contains("workers.stripes") },
            reset.lines.toString(),
        )
    }

    @Test
    fun `violations round-trip through history and cap at 500`() {
        val uuid = "11111111-1111-1111-1111-111111111111"
        // Recent-but-distinct timestamps: GuardStore also prunes by history.retention-days (default
        // 30 days) against a real clock, so epochMs must stay within that window for this test to
        // isolate the count cap specifically, not incidentally trigger age-based pruning too.
        val base = System.currentTimeMillis() - 100_000L
        repeat(505) { i ->
            val result = recording.run(
                "guard.store.violation",
                """{"serverId":"paper-1","uuid":"$uuid","name":"Steve","check":"movement.fly.a",""" +
                    """"vl":${i + 1}.0,"confidence":0.5,"epochMs":${base + i},"details":"t$i"}""",
            )
            assertTrue(result.success)
            assertEquals("""{"ok":true}""", result.lines.single())
        }

        // capped at the newest 500 entries, oldest dropped
        val stored = json.decodeFromString<List<GuardViolation>>(recording.storage.read("violations.$uuid")!!)
        assertEquals(500, stored.size)
        assertEquals(base + 5, stored.first().epochMs)

        // newest first, requested limit honored
        val history = json.parseToJsonElement(recording.run("guard.query.history", uuid, "10").lines.single())
        val violations = history.jsonObject.getValue("violations").jsonArray
        assertEquals(10, violations.size)
        assertEquals(base + 504, violations.first().jsonObject.getValue("epochMs").jsonPrimitive.long)

        // limit is capped at 100
        val capped = json.parseToJsonElement(recording.run("guard.query.history", uuid, "1000").lines.single())
        assertEquals(100, capped.jsonObject.getValue("violations").jsonArray.size)

        assertFalse(recording.run("guard.store.violation", "not json").success)
    }

    @Test
    fun `incidents alert only staff with permission and are queryable`() {
        recording.online += OnlinePlayer("Mod")
        recording.online += OnlinePlayer("Steve")
        recording.permissionCheck = { player, permission -> player == "Mod" && permission == "iguard.alerts" }

        val uuid = "22222222-2222-2222-2222-222222222222"
        val result = recording.run(
            "guard.store.incident",
            """{"id":"inc-1","serverId":"paper-1","uuid":"$uuid","name":"Steve","check":"combat.reach.a",""" +
                """"confidence":0.874,"epochMs":1000,"summary":"reach burst"}""",
        )
        assertTrue(result.success)
        assertEquals("""{"ok":true}""", result.lines.single())

        val alerts = recording.invocations.filter { it.action == "player.message" }
        assertEquals(1, alerts.size)
        assertEquals("Mod", alerts.single().arguments.first())
        val text = alerts.single().arguments[1]
        assertContains(text, "Steve")
        assertContains(text, "combat.reach.a")
        assertContains(text, "87%") // confidence rounded to whole percent
        assertContains(text, "paper-1")

        // stored per player and in the global recent ring
        val perPlayer = json.parseToJsonElement(recording.run("guard.query.incidents", uuid, "10").lines.single())
        assertEquals(1, perPlayer.jsonObject.getValue("incidents").jsonArray.size)
        val all = json.parseToJsonElement(recording.run("guard.query.incidents", "all", "10").lines.single())
        val ring = all.jsonObject.getValue("incidents").jsonArray
        assertEquals(1, ring.size)
        assertEquals("inc-1", ring.single().jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `ban kicks the player, denies joins and answers activeban`() {
        val uuid = "33333333-3333-3333-3333-333333333333"
        val now = System.currentTimeMillis()
        val result = recording.run(
            "guard.store.ban",
            """{"uuid":"$uuid","name":"Evil","reason":"cheating","actor":"IGuard","hours":0,"epochMs":$now}""",
        )
        assertTrue(result.success)
        assertEquals("""{"ok":true}""", result.lines.single())

        // enforced immediately via player.kick with the localized screen
        val kick = recording.invocations.single { it.action == "player.kick" }
        assertEquals("Evil", kick.arguments.first())
        assertContains(kick.arguments[1], "Banned")
        assertContains(kick.arguments[1], "cheating")
        assertContains(kick.arguments[1], "never") // hours 0 = permanent

        // join gate denies, by uuid and by name fallback
        val gate = recording.joinGates.single()
        val denied = gate.check(JoinRequest("Evil", uuid))
        assertFalse(denied.allowed)
        assertContains(denied.message.orEmpty(), "Banned")
        assertFalse(gate.check(JoinRequest("evil")).allowed)

        val active = json.parseToJsonElement(recording.run("guard.query.activeban", uuid).lines.single()).jsonObject
        assertTrue(active.getValue("active").jsonPrimitive.boolean)
        assertEquals("cheating", active.getValue("reason").jsonPrimitive.content)
        assertEquals(0L, active.getValue("expiresAtEpochMs").jsonPrimitive.long)

        // ban is logged in the punishment log
        val log = json.decodeFromString<List<GuardPunishment>>(recording.storage.read("punishments")!!)
        assertEquals(listOf("ban"), log.map { it.type })

        // unban lifts the gate and answers inactive
        assertTrue(recording.run("guard.store.unban", uuid, "Evil").success)
        assertTrue(gate.check(JoinRequest("Evil", uuid)).allowed)
        val cleared = json.parseToJsonElement(recording.run("guard.query.activeban", uuid).lines.single()).jsonObject
        assertFalse(cleared.getValue("active").jsonPrimitive.boolean)
        val logAfter = json.decodeFromString<List<GuardPunishment>>(recording.storage.read("punishments")!!)
        assertEquals(listOf("ban", "unban"), logAfter.map { it.type })
    }

    @Test
    fun `expired bans are pruned lazily and temp bans carry their expiry`() {
        val expiredUuid = "44444444-4444-4444-4444-444444444444"
        val past = System.currentTimeMillis() - 7_200_000
        recording.run(
            "guard.store.ban",
            """{"uuid":"$expiredUuid","name":"Old","reason":"x","actor":"IGuard","hours":1,"epochMs":$past}""",
        )
        val expired = json.parseToJsonElement(
            recording.run("guard.query.activeban", expiredUuid).lines.single(),
        ).jsonObject
        assertFalse(expired.getValue("active").jsonPrimitive.boolean)
        assertTrue(recording.joinGates.single().check(JoinRequest("Old", expiredUuid)).allowed)
        // pruned from the bans document entirely
        assertEquals(null, recording.storage.read("bans"))

        val freshUuid = "55555555-5555-5555-5555-555555555555"
        val now = System.currentTimeMillis()
        recording.run(
            "guard.store.ban",
            """{"uuid":"$freshUuid","name":"Fresh","reason":"y","actor":"IGuard","hours":2,"epochMs":$now}""",
        )
        val active = json.parseToJsonElement(
            recording.run("guard.query.activeban", freshUuid).lines.single(),
        ).jsonObject
        assertTrue(active.getValue("active").jsonPrimitive.boolean)
        assertEquals(now + 7_200_000, active.getValue("expiresAtEpochMs").jsonPrimitive.long)
        assertFalse(recording.joinGates.single().check(JoinRequest("Fresh", freshUuid)).allowed)
    }

    @Test
    fun `replay round-trip`() {
        assertEquals("""{"payload":null}""", recording.run("guard.query.replay", "inc-9").lines.single())
        val stored = recording.run("guard.store.replay", "inc-9", "AAECAw==")
        assertTrue(stored.success)
        assertEquals("""{"ok":true}""", stored.lines.single())
        assertEquals("""{"payload":"AAECAw=="}""", recording.run("guard.query.replay", "inc-9").lines.single())
    }

    @Test
    fun `punishment log stores entries and caps at 500`() {
        repeat(505) { i ->
            val result = recording.run(
                "guard.store.punishment",
                """{"uuid":"u","name":"Steve","type":"warn","reason":"r$i","actor":"IGuard","hours":0,"epochMs":$i}""",
            )
            assertTrue(result.success)
        }
        val log = json.decodeFromString<List<GuardPunishment>>(recording.storage.read("punishments")!!)
        assertEquals(500, log.size)
        assertEquals(5L, log.first().epochMs)
        assertEquals(504L, log.last().epochMs)
    }
}
