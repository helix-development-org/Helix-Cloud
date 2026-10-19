package org.helix.addons.feedback

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.helix.addons.feedback.store.DocumentFeedbackStore
import org.helix.api.storage.InMemoryAddonStorage
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TestClock(var instant: Instant = Instant.parse("2026-10-19T10:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant, zone)
    override fun instant(): Instant = instant
    fun advanceSeconds(seconds: Long) {
        instant = instant.plusSeconds(seconds)
    }
}

class FeedbackServiceTest {
    private val clock = TestClock()
    private val notified = mutableListOf<Pair<String, String>>()
    private var logLines: List<String> = emptyList()
    private val events = mutableListOf<FeedbackEvent>()
    private val service = FeedbackService(
        DocumentFeedbackStore(InMemoryAddonStorage()),
        clock,
        { logLines },
        notify = { category, key, _ -> notified += category to key },
        events = { events += it },
    )

    private val alice = PlayerRef("uuid-alice", "Alice")
    private val bob = PlayerRef("uuid-bob", "Bob")
    private val aliceView = Viewer(alice, false)
    private val bobView = Viewer(bob, false)
    private val staff = Viewer(PlayerRef("uuid-staff", "Mod"), true)

    private fun report(by: PlayerRef = alice, severity: String = Severities.ANNOYING, viewer: Viewer = Viewer(by, false)): Report {
        val r = service.create(NewReport(by, severity, "citybuild", "Truhe verschwindet", "Beim Öffnen weg"), viewer)
        return assertIs<Outcome.Ok<Report>>(r).value
    }

    @Test
    fun `transitions follow the workflow`() {
        assertTrue(Statuses.allowed(Statuses.NEW, Statuses.CONFIRMED))
        assertTrue(Statuses.allowed(Statuses.FIXED, Statuses.CLOSED))
        assertTrue(Statuses.allowed(Statuses.REJECTED, Statuses.NEW))
        assertFalse(Statuses.allowed(Statuses.NEW, Statuses.CLOSED))
        assertFalse(Statuses.allowed(Statuses.CLOSED, Statuses.NEW))
        val r = report()
        val bad = service.status(r.nr, Statuses.CLOSED, staff)
        assertEquals("invalid_transition", assertIs<Outcome.Fail>(bad).code)
        assertEquals("forbidden", assertIs<Outcome.Fail>(service.status(r.nr, Statuses.CONFIRMED, aliceView)).code)
    }

    @Test
    fun `points are granted on confirmation and revoked on rejection`() {
        val r = report(severity = Severities.BLOCKING)
        service.status(r.nr, Statuses.CONFIRMED, staff)
        assertEquals(4, service.top().single().total)
        service.status(r.nr, Statuses.IN_PROGRESS, staff)
        service.status(r.nr, Statuses.CONFIRMED, staff)
        assertEquals(4, service.top().single().total, "moving back to confirmed does not grant twice")
        service.status(r.nr, Statuses.REJECTED, staff)
        assertTrue(service.top().isEmpty())
        service.status(r.nr, Statuses.NEW, staff)
        service.status(r.nr, Statuses.CONFIRMED, staff)
        assertEquals(4, service.top().single().total, "reopened and re-confirmed earns again")
    }

    @Test
    fun `duplicates earn nothing and point to the original`() {
        val first = report()
        clock.advanceSeconds(120)
        val second = report(by = bob)
        val dup = assertIs<Outcome.Ok<Report>>(service.duplicate(second.nr, first.nr, staff)).value
        assertEquals(Statuses.DUPLICATE, dup.status)
        assertEquals(first.nr, dup.duplicateOf)
        assertEquals(0, dup.points)
        assertEquals("invalid_duplicate", assertIs<Outcome.Fail>(service.duplicate(first.nr, first.nr, staff)).code)
    }

    @Test
    fun `reporter is notified about status changes`() {
        val r = report()
        service.status(r.nr, Statuses.CONFIRMED, staff)
        val n = service.notifications(alice.uuid!!)
        assertEquals(listOf(Statuses.CONFIRMED), n.map { it.status })
        assertEquals(1, service.ack(alice.uuid!!, emptySet()))
        assertTrue(service.notifications(alice.uuid!!).isEmpty())
        assertTrue(notified.any { it.second == "notify.status" })
    }

    @Test
    fun `cooldown and daily limit stop spam`() {
        report()
        val again = service.create(NewReport(alice, Severities.MINOR, "lobby", "Nochmal", ""), aliceView)
        assertEquals("cooldown", assertIs<Outcome.Fail>(again).code)
        repeat(19) {
            clock.advanceSeconds(61)
            report()
        }
        clock.advanceSeconds(61)
        val limited = service.create(NewReport(alice, Severities.MINOR, "lobby", "Zu viel", ""), aliceView)
        assertEquals("daily_limit", assertIs<Outcome.Fail>(limited).code)
        assertIs<Outcome.Ok<Report>>(service.create(NewReport(alice, Severities.MINOR, "lobby", "Team", ""), staff))
    }

    @Test
    fun `closed test phase rejects player reports`() {
        service.patchSettings(JsonObject(mapOf("testphase" to JsonPrimitive(false))))
        val r = service.create(NewReport(alice, Severities.MINOR, "lobby", "Titel", ""), aliceView)
        assertEquals("testphase_closed", assertIs<Outcome.Fail>(r).code)
    }

    @Test
    fun `validation rejects unknown values`() {
        val r = service.create(NewReport(alice, "huge", "lobby", "Titel", ""), aliceView)
        assertEquals("invalid_severity", assertIs<Outcome.Fail>(r).code)
        val t = service.create(NewReport(alice, Severities.MINOR, "lobby", " ", ""), aliceView)
        assertEquals("invalid_title", assertIs<Outcome.Fail>(t).code)
    }

    @Test
    fun `players only see their own reports and exploits stay hidden`() {
        val exploit = report(severity = Severities.EXPLOIT)
        clock.advanceSeconds(61)
        report(by = bob)
        assertEquals(1, service.list(ListFilter(), bobView).first)
        assertEquals(1, service.list(ListFilter(), aliceView).first)
        assertEquals(2, service.list(ListFilter(), staff).first)
        assertEquals("forbidden", assertIs<Outcome.Fail>(service.get(exploit.nr, bobView)).code)
        assertIs<Outcome.Ok<ReportDetails>>(service.get(exploit.nr, aliceView))
        assertTrue(notified.any { it.first == "bugreport-staff" })
        assertEquals("forbidden", assertIs<Outcome.Fail>(service.comment(exploit.nr, "Ich auch", bobView)).code)
    }

    @Test
    fun `automatic reports are deduplicated by hash`() {
        val first = assertIs<Outcome.Ok<Pair<Report, Boolean>>>(service.auto(AutoReport("abc", "NPE in Shop", "trace", player = alice))).value
        assertTrue(first.second)
        val second = assertIs<Outcome.Ok<Pair<Report, Boolean>>>(service.auto(AutoReport("abc", "NPE in Shop", "trace", player = bob))).value
        assertFalse(second.second)
        assertEquals(first.first.nr, second.first.nr)
        assertEquals(2, second.first.count)
        assertEquals(setOf(alice.uuid, bob.uuid), second.first.affected.toSet())
        service.status(first.first.nr, Statuses.CONFIRMED, staff)
        service.status(first.first.nr, Statuses.FIXED, staff)
        service.status(first.first.nr, Statuses.CLOSED, staff)
        val third = assertIs<Outcome.Ok<Pair<Report, Boolean>>>(service.auto(AutoReport("abc", "NPE in Shop", "trace"))).value
        assertTrue(third.second, "a closed report starts a new one")
        assertTrue(service.top().isEmpty(), "automatic reports never earn points")
    }

    @Test
    fun `chunked uploads are assembled and size limited`() {
        val r = report()
        assertEquals(1, assertIs<Outcome.Ok<Int>>(service.chunk("u1", 1, 2, byteArrayOf(3, 4))).value)
        assertEquals("incomplete", assertIs<Outcome.Fail>(service.finish("u1", r.nr, "shot.jpg", "image/jpeg", aliceView)).code)
        service.chunk("u1", 0, 2, byteArrayOf(1, 2))
        val att = assertIs<Outcome.Ok<Attachment>>(service.finish("u1", r.nr, "shot.jpg", "image/jpeg", aliceView)).value
        assertEquals(4, att.size)
        assertEquals(listOf<Byte>(1, 2, 3, 4), service.attachment(att.id)!!.second.toList())
        service.patchSettings(JsonObject(mapOf("maxAttachmentBytes" to JsonPrimitive(3))))
        assertEquals("too_large", assertIs<Outcome.Fail>(service.chunk("u2", 0, 1, byteArrayOf(1, 2, 3, 4))).code)
        service.chunk("u3", 0, 2, byteArrayOf(1))
        clock.advanceSeconds(11 * 60)
        assertEquals("not_found", assertIs<Outcome.Fail>(service.finish("u3", r.nr, "x", "image/png", aliceView)).code)
        assertEquals("forbidden", assertIs<Outcome.Fail>(service.attach(r.nr, "x", "image/png", byteArrayOf(1), bobView)).code)
    }

    @Test
    fun `log lines of the reporting service are attached`() {
        logLines = listOf(
            "[09:40:00 ERROR]: old error",
            "[09:55:00 INFO]: info line",
            "[09:56:00 WARN]: recent warning",
            "[09:57:00 ERROR]: broken",
            "\tat de.tytoss.Foo.bar(Foo.kt:1)",
            "[09:58:00 INFO]: done",
            "\tat not.kept(Bar.kt:2)",
        )
        val ctx = JsonObject(mapOf("service" to JsonPrimitive("CityBuild-1"), "serverTime" to JsonPrimitive("10:00:00")))
        val r = assertIs<Outcome.Ok<Report>>(
            service.create(NewReport(alice, Severities.CRASH, "citybuild", "Absturz", "", context = ctx), aliceView),
        ).value
        val logs = (r.context["logs"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("[09:56:00 WARN]: recent warning", "[09:57:00 ERROR]: broken", "\tat de.tytoss.Foo.bar(Foo.kt:1)"), logs)
        assertFalse(service.list(ListFilter(), staff).second.single().context.containsKey("logs"), "lists omit log lines")
    }

    @Test
    fun `log excerpt wraps around midnight and keeps the newest lines`() {
        val lines = listOf("[23:58:00 WARN]: before midnight", "[00:01:00 ERROR]: after midnight", "[12:00:00 ERROR]: noon")
        assertEquals(lines.take(2), LogExcerpt.filter(lines, LocalTime.of(0, 2), 10, 100))
        assertEquals(listOf("[00:01:00 ERROR]: after midnight"), LogExcerpt.filter(lines, LocalTime.of(0, 2), 10, 1))
    }

    @Test
    fun `agreements and rewards are tracked`() {
        assertEquals(1 to null, service.agreement("uuid-alice"))
        service.accept("uuid-alice")
        assertEquals(1 to 1, service.agreement("uuid-alice"))
        val reward = service.grant("uuid-alice", JsonObject(mapOf("kronen" to JsonPrimitive(500))))
        assertEquals(listOf(reward.id), service.pending("uuid-alice").map { it.id })
        assertIs<Outcome.Ok<PendingReward>>(service.claim("uuid-alice", reward.id))
        assertEquals("claimed", assertIs<Outcome.Fail>(service.claim("uuid-alice", reward.id)).code)
        assertTrue(service.pending("uuid-alice").isEmpty())
    }

    @Test
    fun `events mirror creation, status, comments and attachments`() {
        val r = report()
        service.status(r.nr, Statuses.CONFIRMED, staff)
        service.comment(r.nr, "from discord", aliceView, via = "discord")
        service.attach(r.nr, "a.png", "image/png", byteArrayOf(1, 2), aliceView)
        assertEquals(listOf("created", "status", "comment", "attachment"), events.map { it.type })
        assertEquals(Statuses.NEW, events[1].from)
        assertEquals(Statuses.CONFIRMED, events[1].to)
        assertEquals("discord", events[2].via)
        assertEquals(null, events[3].via)
    }

    @Test
    fun `discord threads are linked and found`() {
        val r = report()
        assertEquals(null, service.byDiscordThread("123"))
        service.linkDiscord(r.nr, "123")
        assertEquals(r.nr, service.byDiscordThread("123")?.nr)
        service.linkDiscord(r.nr, "")
        assertEquals(null, service.byDiscordThread("123"))
        assertTrue(service.linkDiscord(999, "1") is Outcome.Fail)
    }
}
