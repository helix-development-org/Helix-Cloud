package org.helix.addons.feedback.paper

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FeedbackLogicTest {
    @Test
    fun `form needs severity, title and description`() {
        val empty = ReportDraft()
        assertEquals(
            listOf("form.error.severity", "form.error.title-short", "form.error.description-short"),
            FormValidation.problems(empty),
        )
        val ok = ReportDraft(severity = Severity.MINOR, title = "Tür klemmt", description = "Die Rathaustür öffnet nicht mehr")
        assertTrue(FormValidation.problems(ok).isEmpty())
        val long = ok.copy(title = "x".repeat(FormValidation.TITLE_MAX + 1))
        assertEquals(listOf("form.error.title-long"), FormValidation.problems(long))
    }

    @Test
    fun `clean strips colour codes, tags and extra spaces`() {
        assertEquals("Hallo Welt", FormValidation.clean("  §cHallo   <red>Welt</red>  "))
    }

    @Test
    fun `cycling wraps around and starts at the first value`() {
        assertEquals(Severity.MINOR, FormValidation.next(Severity.entries, null))
        assertEquals(Severity.MINOR, FormValidation.next(Severity.entries, Severity.EXPLOIT))
        assertEquals(Category.LOBBY, FormValidation.next(Category.entries, Category.CITYBUILD))
    }

    @Test
    fun `wire ids match the node contract`() {
        assertEquals(listOf("minor", "annoying", "blocking", "crash", "exploit"), Severity.entries.map { it.wire })
        assertEquals(listOf("mineclash", "citybuild", "lobby", "network", "graphics", "other"), Category.entries.map { it.wire })
        assertEquals(ReportStatus.NOT_A_BUG, ReportStatus.of("not_a_bug"))
        assertEquals(ReportStatus.NEW, ReportStatus.of("garbage"))
        assertTrue(Severity.EXPLOIT.staffOnly)
    }

    @Test
    fun `category is guessed from the task`() {
        assertEquals(Category.CITYBUILD, Category.forTask("CityBuild"))
        assertEquals(Category.MINECLASH, Category.forTask("MineClash-War"))
        assertEquals(Category.LOBBY, Category.forTask("Lobby"))
        assertEquals(Category.OTHER, Category.forTask("Proxy"))
    }

    @Test
    fun `context json has the contract fields`() {
        val snapshot = ContextSnapshot(
            service = "CityBuild-2", task = "CityBuild", world = "plots", x = 10.04, y = 64.0, z = -3.26, yaw = 90.12f, pitch = 5f,
            gamemode = "survival", tps = 21.3, mspt = 12.345, ping = 42, clientVersion = "1.21.11", clientBrand = "fabric",
            clientFamily = "fabric", labymod = true, versions = mapOf("B" to "2", "A" to "1"), screenshot = true,
        )
        val json = ContextBuilder.toJson(snapshot)
        assertEquals("CityBuild-2", json["service"]!!.jsonPrimitive.content)
        assertEquals(10.0, json["x"]!!.jsonPrimitive.double)
        assertEquals(20.0, json["tps"]!!.jsonPrimitive.double)
        assertEquals(12.3, json["mspt"]!!.jsonPrimitive.double)
        assertEquals(42, json["ping"]!!.jsonPrimitive.int)
        assertTrue(json["client"]!!.jsonObject["labymod"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("A", "B"), json["versions"]!!.jsonObject.keys.toList())
    }

    @Test
    fun `version string adds the commit from the manifest`() {
        assertEquals("1.0", ContextBuilder.versionOf("1.0", emptyMap()))
        assertEquals("0.91.0@abcdef1234", ContextBuilder.versionOf("1.0", mapOf("Implementation-Version" to "0.91.0", "Git-Commit" to "abcdef1234567")))
    }

    @Test
    fun `stack hash ignores line numbers, ids and lambda suffixes`() {
        fun error(message: String, line: Int, lambda: String, method: String = "buy") = IllegalStateException(message).apply {
            stackTrace = arrayOf(
                StackTraceElement("de.tytoss.citybuild.shop.Shop\$lambda\$$lambda", method, "Shop.kt", line),
                StackTraceElement("java.lang.Thread", "run", "Thread.java", 1),
            )
        }
        val a = error("Entity 1234 at 0xdeadbeef failed", 10, "1")
        val b = error("Entity 98 at 0xcafebabe failed", 99, "7")
        val c = error("Entity 1234 at 0xdeadbeef failed", 10, "1", method = "sell")
        assertEquals(StackTraces.hash(a), StackTraces.hash(b))
        assertNotEquals(StackTraces.hash(a), StackTraces.hash(c))
        assertEquals(16, StackTraces.hash(a).length)
        assertTrue(StackTraces.title(a).startsWith("IllegalStateException in Shop"))
    }

    @Test
    fun `recent hashes admit once per window`() {
        var now = 0L
        val recent = RecentHashes(windowMillis = 1000) { now }
        assertTrue(recent.admit("x"))
        assertFalse(recent.admit("x"))
        assertTrue(recent.admit("y"))
        now = 1000
        assertTrue(recent.admit("x"))
    }

    @Test
    fun `freeze allows only the agreement command and looking around`() {
        assertTrue(FreezeRules.commandAllowed("/testervereinbarung annehmen"))
        assertTrue(FreezeRules.commandAllowed("/helixfeedback:testervereinbarung"))
        assertFalse(FreezeRules.commandAllowed("/spawn"))
        assertFalse(FreezeRules.blocksMove(1.0, 64.0, 1.0, 1.0, 64.0, 1.0))
        assertTrue(FreezeRules.blocksMove(1.0, 64.0, 1.0, 1.5, 64.0, 1.0))
        assertTrue(FreezeRules.blocksMove(1.0, 64.0, 1.0, 1.0, 65.0, 1.0))
        assertFalse(FreezeRules.blocksMove(1.0, 64.0, 1.0, 1.0, 63.0, 1.0))
    }

    @Test
    fun `agreement is required for testers below the current version only`() {
        assertTrue(FreezeRules.mustAccept(true, null, 1, false))
        assertTrue(FreezeRules.mustAccept(true, 1, 2, false))
        assertFalse(FreezeRules.mustAccept(true, 2, 2, false))
        assertFalse(FreezeRules.mustAccept(true, null, 1, true))
        assertFalse(FreezeRules.mustAccept(false, null, 1, false))
    }

    @Test
    fun `test tools need the test phase and the permission`() {
        assertTrue(TestToolRules.available(testphase = true, hasPermission = true))
        assertFalse(TestToolRules.available(testphase = false, hasPermission = true))
        assertFalse(TestToolRules.available(testphase = true, hasPermission = false))
        val config = TestToolsConfig(flyWorlds = setOf("plots", "Lobby"))
        assertTrue(TestToolRules.flyAllowed(config, "lobby"))
        assertFalse(TestToolRules.flyAllowed(config, "farm"))
        assertTrue(TestToolRules.validPointName("markt_1"))
        assertFalse(TestToolRules.validPointName("mit leerzeichen"))
    }

    @Test
    fun `node error codes map to chat keys`() {
        assertEquals("error.cooldown", FeedbackMenus.errorKey("cooldown"))
        assertEquals("error.invalid", FeedbackMenus.errorKey("invalid_title"))
        assertEquals("error.unavailable", FeedbackMenus.errorKey("unavailable"))
        assertEquals("error.not-tester", FeedbackMenus.errorKey("not_tester"))
        assertEquals("error.agreement", FeedbackMenus.errorKey("agreement_required"))
        assertEquals("error.attachment-limit", FeedbackMenus.errorKey("attachment_limit"))
    }

    @Test
    fun `test flight is revoked outside allowed worlds and after the test phase`() {
        val config = TestToolsConfig()
        assertEquals(setOf("lobby", "plots"), config.flyWorlds)
        assertTrue(TestToolRules.keepsFlight(true, true, TestToolRules.flyAllowed(config, "plots")))
        assertFalse(TestToolRules.keepsFlight(true, true, TestToolRules.flyAllowed(config, "farm")))
        assertFalse(TestToolRules.keepsFlight(true, true, TestToolRules.flyAllowed(config, "spawn")))
        assertFalse(TestToolRules.keepsFlight(false, true, true))
        assertFalse(TestToolRules.keepsFlight(true, false, true))
    }

    @Test
    fun `freeze fails closed when the node does not answer`() {
        assertTrue(FreezeRules.frozenWith(true, false, null))
        assertFalse(FreezeRules.frozenWith(true, true, null), "staff is never frozen")
        assertFalse(FreezeRules.frozenWith(false, false, null))
        assertFalse(FreezeRules.frozenWith(true, false, 2 to 2))
        assertTrue(FreezeRules.frozenWith(true, false, 1 to 2))
    }

    @Test
    fun `stack hash ignores the exception message`() {
        val a = IllegalStateException("Player Alice not found")
        val b = IllegalStateException("Player Bob not found")
        b.stackTrace = a.stackTrace
        assertEquals(StackTraces.hash(a), StackTraces.hash(b))
    }
}
