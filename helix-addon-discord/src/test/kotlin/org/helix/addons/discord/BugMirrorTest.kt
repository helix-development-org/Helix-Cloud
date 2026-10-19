package org.helix.addons.discord

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeFeedback : FeedbackPort {
    val reports = mutableMapOf<Long, BugReport>()
    val comments = mutableMapOf<Long, MutableList<BugComment>>()
    val created = mutableListOf<Triple<BugPlayer, String, String>>()
    val commented = mutableListOf<Pair<Long, String>>()
    val attached = mutableListOf<Pair<Long, String>>()
    var createResult: BugCallResult? = null
    private var next = 100L

    override fun get(nr: Long): BugDetail? = reports[nr]?.let { BugDetail(it, comments[nr].orEmpty()) }

    override fun create(reporter: BugPlayer, severity: String, category: String, title: String, description: String): BugCallResult {
        createResult?.let { return it }
        created += Triple(reporter, severity, category)
        val nr = next++
        reports[nr] = BugReport(nr, severity = severity, category = category, title = title, description = description, reporter = reporter, source = "discord")
        return BugCallResult(true, nr = nr)
    }

    override fun comment(nr: Long, author: BugPlayer, text: String): BugCallResult {
        val report = reports[nr] ?: return BugCallResult(false, "not_found")
        if (report.reporter?.uuid != author.uuid) return BugCallResult(false, "forbidden")
        commented += nr to text
        return BugCallResult(true)
    }

    override fun attach(nr: Long, author: BugPlayer, file: BugFile): BugCallResult {
        attached += nr to file.name
        return BugCallResult(true)
    }

    override fun attachment(attachment: BugAttachment): BugFile = BugFile(attachment.name, attachment.mime, byteArrayOf(1))

    override fun link(nr: Long, threadId: String) {
        reports[nr] = reports.getValue(nr).copy(discordThread = threadId)
    }

    override fun byThread(threadId: String): BugReport? = reports.values.firstOrNull { it.discordThread == threadId }

    override fun top(limit: Int): List<BugTopEntry> = listOf(BugTopEntry(1, "u1", "Alice", 12), BugTopEntry(2, "u2", "Bob", 4))
}

private class FakeForum : ForumPort {
    val forums = setOf("forum")
    val available = mapOf("kleinigkeit" to "t-minor", "absturz" to "t-crash", "citybuild" to "t-cb", "neu" to "t-new", "bestätigt" to "t-confirmed", "geschlossen" to "t-closed")
    val opened = mutableListOf<Triple<String, String, List<String>>>()
    val posts = mutableListOf<Pair<String, String>>()
    val updates = mutableListOf<Triple<String, String?, Boolean?>>()
    val sent = mutableListOf<Pair<String, String>>()
    var lastTags: List<String>? = null
    private var threads = 0

    override suspend fun isForum(channelId: String) = channelId in forums

    override suspend fun tags(channelId: String) = if (channelId in forums) available else emptyMap()

    override suspend fun openThread(channelId: String, title: String, content: String, tags: List<String>, files: List<BugFile>): String {
        opened += Triple(channelId, title, tags)
        return "thread-${++threads}"
    }

    override suspend fun post(threadId: String, content: String, files: List<BugFile>) {
        posts += threadId to content
    }

    override suspend fun update(threadId: String, name: String?, tags: List<String>?, closed: Boolean?) {
        updates += Triple(threadId, name, closed)
        if (tags != null) lastTags = tags
    }

    override suspend fun download(url: String, maxBytes: Int) = byteArrayOf(1, 2, 3)

    override suspend fun send(channelId: String, content: String) {
        sent += channelId to content
    }
}

class BugMirrorTest {
    private val feedback = FakeFeedback()
    private val forum = FakeForum()
    private var config = DiscordConfig(bugForumChannelId = "forum", bugStaffChannelId = "staff", bugBoardChannelId = "board")
    private val alice = BugPlayer("uuid-alice", "Alice")
    private val mirror = BugMirror(
        config = { config },
        feedback = feedback,
        forum = forum,
        playerOf = { id -> if (id == "d-alice") alice else null },
        discordOf = { uuid -> if (uuid == "uuid-alice") "d-alice" else null },
        text = { key, params -> key + params.entries.joinToString("") { "|${it.key}=${it.value}" } },
    )

    private fun report(nr: Long, severity: String = "crash", category: String = "citybuild", reporter: BugPlayer? = alice) =
        BugReport(
            nr,
            severity = severity,
            category = category,
            title = "Plot weg",
            description = "Mein Plot ist weg",
            reporter = reporter,
            context = buildJsonObject {
                put("service", JsonPrimitive("CityBuild-1"))
                put("world", JsonPrimitive("plots"))
                put("x", JsonPrimitive(10.7))
                put("y", JsonPrimitive(64))
                put("z", JsonPrimitive(-3))
            },
        ).also { feedback.reports[nr] = it }

    @Test
    fun `new in-game reports open a tagged forum thread and are linked`() = runBlocking {
        report(5)
        mirror.onEvent(BugEvent("created", 5, source = "ingame"))
        val (channel, title, tags) = forum.opened.single()
        assertEquals("forum", channel)
        assertEquals("#5 Plot weg", title)
        assertEquals(listOf("t-crash", "t-cb", "t-new"), tags)
        assertEquals("thread-1", feedback.reports.getValue(5).discordThread)
        val starter = mirror.starter(feedback.reports.getValue(5))
        assertTrue("<@d-alice>" in starter, starter)
        assertTrue("CityBuild-1 · plots (10, 64, -3)" in starter, starter)
    }

    @Test
    fun `exploits only go to the staff channel and are dropped without one`() = runBlocking {
        report(6, severity = "exploit")
        mirror.onEvent(BugEvent("created", 6, source = "ingame"))
        assertEquals("staff", forum.opened.single().first)
        assertEquals(emptyList(), forum.opened.single().third)
        config = config.copy(bugStaffChannelId = "")
        report(7, severity = "exploit")
        mirror.onEvent(BugEvent("created", 7, source = "ingame"))
        assertEquals(1, forum.opened.size)
    }

    @Test
    fun `status changes post, retag and close the thread`() = runBlocking {
        report(8)
        mirror.onEvent(BugEvent("created", 8, source = "panel"))
        feedback.reports[8] = feedback.reports.getValue(8).copy(status = "closed")
        mirror.onEvent(BugEvent("status", 8, from = "fixed", to = "closed", actor = "Mod"))
        assertTrue(forum.posts.single().second.startsWith("bug.status|"))
        assertEquals(true, forum.updates.last().third)
        assertEquals(listOf("t-crash", "t-cb", "t-closed"), forum.lastTags)
        feedback.reports[8] = feedback.reports.getValue(8).copy(status = "in_progress")
        mirror.onEvent(BugEvent("status", 8, from = "closed", to = "in_progress", actor = "Mod"))
        assertTrue(forum.updates.any { it.third == false })
    }

    @Test
    fun `own discord changes are not echoed`() = runBlocking {
        report(9).let { feedback.link(9, "thread-x") }
        mirror.onEvent(BugEvent("created", 9, source = "discord"))
        mirror.onEvent(BugEvent("comment", 9, commentId = "c1", via = "discord"))
        mirror.onEvent(BugEvent("attachment", 9, attachmentId = "a1", via = "discord"))
        assertTrue(forum.opened.isEmpty() && forum.posts.isEmpty())
        feedback.comments[9] = mutableListOf(BugComment("c2", BugPlayer(null, "Mod"), staff = true, text = "Schauen wir uns an"))
        mirror.onEvent(BugEvent("comment", 9, commentId = "c2"))
        assertEquals("thread-x", forum.posts.single().first)
        assertTrue(forum.posts.single().second.startsWith("bug.comment.staff|"))
    }

    @Test
    fun `forum posts of linked testers become reports with tag classification`() = runBlocking {
        val post = BugMessage(
            threadId = "t1", messageId = "t1", parentId = "forum", threadName = "Absturz beim Plot", appliedTags = listOf("t-crash", "t-cb"),
            authorId = "d-alice", authorName = "alice", authorIsBot = false, content = "Server stürzt ab",
            files = listOf(BugMessage.RemoteFile("shot.png", "image/png", "https://cdn/x", 3)),
        )
        mirror.onMessage(post)
        val (who, severity, category) = feedback.created.single()
        assertEquals(alice, who)
        assertEquals("crash", severity)
        assertEquals("citybuild", category)
        val nr = feedback.reports.keys.single()
        assertEquals("t1", feedback.reports.getValue(nr).discordThread)
        assertEquals("#$nr Absturz beim Plot", forum.updates.single().second)
        assertEquals(listOf(nr to "shot.png"), feedback.attached)
        assertTrue(forum.posts.last().second.startsWith("bug.created|nr=$nr"))
        mirror.onMessage(post.copy(messageId = "m2", content = "Noch ein Detail", files = emptyList()))
        assertEquals(listOf(nr to "Noch ein Detail"), feedback.commented)
    }

    @Test
    fun `unlinked users get a hint once per thread and limits are reported`() = runBlocking {
        val post = BugMessage("t2", "t2", "forum", "Bug", emptyList(), "d-bob", "bob", false, "hi", emptyList())
        mirror.onMessage(post)
        assertTrue(forum.posts.single().second.startsWith("bug.link_required|"))
        assertTrue(feedback.created.isEmpty())
        feedback.createResult = BugCallResult(false, "cooldown", "42")
        mirror.onMessage(post.copy(threadId = "t3", messageId = "t3", authorId = "d-alice"))
        assertEquals("bug.error.cooldown|detail=42", forum.posts.last().second)
        mirror.onMessage(post.copy(authorIsBot = true, threadId = "t4", messageId = "t4"))
        assertEquals(2, forum.posts.size)
    }

    @Test
    fun `messages outside the bug channels are ignored`() = runBlocking {
        mirror.onMessage(BugMessage("t5", "t5", "general", "x", emptyList(), "d-alice", "alice", false, "x", emptyList()))
        assertTrue(feedback.created.isEmpty() && forum.posts.isEmpty())
        config = config.copy(bugForumChannelId = "")
        assertFalse(config.bugMirror())
    }

    @Test
    fun `weekly leaderboard is posted once per week`() = runBlocking {
        config = config.copy(bugBoardDay = 1, bugBoardHour = 18, bugBoardZone = "Europe/Berlin")
        val monday = Instant.parse("2026-10-19T17:30:00Z")
        val week = mirror.postBoardIfDue(monday, null)
        assertEquals(BugBoard.weekKey(monday, ZoneId.of("Europe/Berlin")), week)
        assertTrue(forum.sent.single().second.contains("Alice — 12"))
        assertNull(mirror.postBoardIfDue(monday.plusSeconds(600), week))
    }
}
