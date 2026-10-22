package org.helix.addons.discord

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import java.util.Base64

/**
 * The feedback addon as seen by the Discord mirror (node-internal action calls).
 */
interface FeedbackPort {
    /**
     * Loads a report with its comments (staff view).
     *
     * @param nr report number.
     * @return the detail or `null`.
     */
    fun get(nr: Long): BugDetail?

    /**
     * Creates a report on behalf of a linked player (player rights and spam limits apply).
     *
     * @param reporter the linked player.
     * @param severity severity key.
     * @param category category key.
     * @param title title.
     * @param description description.
     * @return the outcome (with [BugCallResult.nr] on success).
     */
    fun create(reporter: BugPlayer, severity: String, category: String, title: String, description: String): BugCallResult

    /**
     * Comments on a report on behalf of a linked player.
     *
     * @param nr report number.
     * @param author the linked player.
     * @param text comment text.
     * @return the outcome.
     */
    fun comment(nr: Long, author: BugPlayer, text: String): BugCallResult

    /**
     * Attaches a file on behalf of a linked player.
     *
     * @param nr report number.
     * @param author the linked player.
     * @param file the file.
     * @return the outcome.
     */
    fun attach(nr: Long, author: BugPlayer, file: BugFile): BugCallResult

    /**
     * Loads attachment bytes.
     *
     * @param attachment attachment metadata.
     * @return the file or `null`.
     */
    fun attachment(attachment: BugAttachment): BugFile?

    /**
     * Stores the thread mirroring a report.
     *
     * @param nr report number.
     * @param threadId Discord thread id.
     */
    fun link(nr: Long, threadId: String)

    /**
     * Finds the report mirrored by a thread.
     *
     * @param threadId Discord thread id.
     * @return the report or `null`.
     */
    fun byThread(threadId: String): BugReport?

    /**
     * Reads the tester leaderboard.
     *
     * @param limit number of entries.
     * @return the entries, best first.
     */
    fun top(limit: Int): List<BugTopEntry>
}

/**
 * [FeedbackPort] over the node action registry (`feedback.*` actions, called as addon).
 *
 * @property actions node action invoker.
 */
class ActionFeedbackPort(private val actions: ActionInvoker) : FeedbackPort {
    private val json = Json { ignoreUnknownKeys = true }

    override fun get(nr: Long): BugDetail? =
        call("feedback.get", nr.toString())?.takeIf { it.success }?.let { decode(it, BugDetail.serializer()) }

    override fun create(reporter: BugPlayer, severity: String, category: String, title: String, description: String): BugCallResult {
        val body = buildJsonObject {
            putJsonObject("reporter") { reporter.uuid?.let { put("uuid", it) }; put("name", reporter.name) }
            put("severity", severity)
            put("category", category)
            put("title", title)
            put("description", description)
            put("source", "discord")
            put("asPlayer", true)
        }
        val result = call("feedback.create", body.toString()) ?: return BugCallResult(false, "unavailable")
        if (!result.success) return failure(result)
        val nr = runCatching { json.parseToJsonElement(result.lines[0]).jsonObject["nr"]?.jsonPrimitive?.longOrNull }.getOrNull()
        return BugCallResult(true, nr = nr)
    }

    override fun comment(nr: Long, author: BugPlayer, text: String): BugCallResult {
        val body = buildJsonObject {
            put("nr", nr)
            putJsonObject("author") { author.uuid?.let { put("uuid", it) }; put("name", author.name) }
            put("text", text)
            put("via", "discord")
            put("asPlayer", true)
        }
        return outcome(call("feedback.comment", body.toString()))
    }

    override fun attach(nr: Long, author: BugPlayer, file: BugFile): BugCallResult {
        val viewer = buildJsonObject {
            author.uuid?.let { put("uuid", it) }
            put("name", author.name)
            put("via", "discord")
            put("asPlayer", true)
        }
        val args = listOf(nr.toString(), file.name.replace(' ', '_'), file.mime, Base64.getEncoder().encodeToString(file.bytes), viewer.toString())
        return outcome(call("feedback.attach", *args.toTypedArray()))
    }

    override fun attachment(attachment: BugAttachment): BugFile? {
        val result = call("feedback.attachment", attachment.id)?.takeIf { it.success && it.lines.size >= 2 } ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(result.lines[1]) }.getOrNull() ?: return null
        return BugFile(attachment.name, result.lines[0], bytes)
    }

    override fun link(nr: Long, threadId: String) {
        call("feedback.discord.link", nr.toString(), threadId)
    }

    override fun byThread(threadId: String): BugReport? =
        call("feedback.discord.find", threadId)?.takeIf { it.success }?.let { decode(it, BugReport.serializer()) }

    override fun top(limit: Int): List<BugTopEntry> =
        call("feedback.top", limit.toString())?.takeIf { it.success }
            ?.let { decode(it, ListSerializer(BugTopEntry.serializer())) }.orEmpty()

    private fun call(action: String, vararg args: String): ActionResult? =
        runCatching { actions.invoke(ActionInvocation(action, args.toList(), ActionSource.ADDON)) }.getOrNull()

    private fun <T> decode(result: ActionResult, serializer: kotlinx.serialization.KSerializer<T>): T? =
        result.lines.firstOrNull()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private fun outcome(result: ActionResult?): BugCallResult = when {
        result == null -> BugCallResult(false, "unavailable")
        result.success -> BugCallResult(true)
        else -> failure(result)
    }

    private fun failure(result: ActionResult): BugCallResult =
        BugCallResult(false, result.lines.getOrNull(0) ?: "error", result.lines.getOrNull(1))
}

/**
 * Discord operations of the bug mirror, decoupled from Kord (fakes in tests).
 */
interface ForumPort {
    /**
     * Whether a channel is a forum channel.
     *
     * @param channelId channel id.
     * @return `true` for forums, `false` for text channels or unknown ids.
     */
    suspend fun isForum(channelId: String): Boolean

    /**
     * Available tags of a forum.
     *
     * @param channelId forum channel id.
     * @return lowercase tag name to tag id.
     */
    suspend fun tags(channelId: String): Map<String, String>

    /**
     * Opens a new thread: a forum post in forums, otherwise a message plus thread in a text channel.
     *
     * @param channelId forum or text channel id.
     * @param title thread title.
     * @param content starter message.
     * @param tags forum tag ids (ignored for text channels).
     * @param files files of the starter message.
     * @return the thread id or `null` on failure.
     */
    suspend fun openThread(channelId: String, title: String, content: String, tags: List<String>, files: List<BugFile>): String?

    /**
     * Posts a message into a thread.
     *
     * @param threadId thread id.
     * @param content text.
     * @param files files.
     */
    suspend fun post(threadId: String, content: String, files: List<BugFile> = emptyList())

    /**
     * Updates a thread.
     *
     * @param threadId thread id.
     * @param name new title, `null` keeps it.
     * @param tags new tag ids, `null` keeps them.
     * @param closed `true` archives and locks, `false` reopens, `null` keeps the state.
     */
    suspend fun update(threadId: String, name: String? = null, tags: List<String>? = null, closed: Boolean? = null)

    /**
     * Downloads a Discord attachment.
     *
     * @param url attachment URL.
     * @param maxBytes size limit.
     * @return the bytes or `null` (too large or failed).
     */
    suspend fun download(url: String, maxBytes: Int): ByteArray?

    /**
     * Sends a plain feed message to a channel (leaderboard).
     *
     * @param channelId channel id.
     * @param content text.
     */
    suspend fun send(channelId: String, content: String)
}
