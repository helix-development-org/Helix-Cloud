package org.helix.addons.discord

import dev.kord.common.entity.AllowedMentionType
import dev.kord.common.entity.ArchiveDuration
import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.channel.thread.ThreadChannel
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.rest.builder.channel.thread.ThreadModifyBuilder
import dev.kord.rest.builder.message.MessageBuilder
import dev.kord.rest.builder.message.allowedMentions
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * [ForumPort] on Kord's REST client.
 *
 * @property kord the logged-in Kord instance.
 * @property feed sends a plain feed line through the bot's batched outbox (leaderboard).
 */
class KordForumPort(private val kord: Kord, private val feed: (channelId: String, text: String) -> Unit) : ForumPort {
    private val forumCache = ConcurrentHashMap<String, Boolean>()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    override suspend fun isForum(channelId: String): Boolean =
        forumCache[channelId] ?: runCatching { kord.rest.channel.getChannel(Snowflake(channelId)).type == ChannelType.GuildForum }
            .getOrDefault(false)
            .also { forumCache[channelId] = it }

    override suspend fun tags(channelId: String): Map<String, String> = runCatching {
        kord.rest.channel.getChannel(Snowflake(channelId)).availableTags.value.orEmpty()
            .associate { it.name.lowercase() to it.id.toString() }
    }.getOrDefault(emptyMap())

    override suspend fun openThread(channelId: String, title: String, content: String, tags: List<String>, files: List<BugFile>): String? =
        runCatching {
            val channel = Snowflake(channelId)
            if (isForum(channelId)) {
                kord.rest.channel.startForumThread(channel, title) {
                    autoArchiveDuration = ArchiveDuration.Week
                    if (tags.isNotEmpty()) appliedTags = tags.take(MAX_TAGS).map(::Snowflake).toMutableList()
                    message { fill(content, files) }
                }.id.toString()
            } else {
                val starter = kord.rest.channel.createMessage(channel) { fill(content, files) }
                kord.rest.channel.startThreadWithMessage(channel, starter.id, title, ArchiveDuration.Week) {}.id.toString()
            }
        }.getOrNull()

    override suspend fun post(threadId: String, content: String, files: List<BugFile>) {
        runCatching { kord.rest.channel.createMessage(Snowflake(threadId)) { fill(content, files) } }
    }

    override suspend fun update(threadId: String, name: String?, tags: List<String>?, closed: Boolean?) {
        val request = ThreadModifyBuilder().apply {
            name?.let { this.name = it }
            tags?.let { appliedTags = it.take(MAX_TAGS).map(::Snowflake).toMutableList() }
            closed?.let {
                archived = it
                locked = it
            }
        }.toRequest()
        runCatching { kord.rest.channel.patchThread(Snowflake(threadId), request) }
    }

    override suspend fun download(url: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            response.body().takeIf { response.statusCode() == 200 && it.size <= maxBytes }
        }.getOrNull()
    }

    override suspend fun send(channelId: String, content: String) {
        feed(channelId, content)
    }

    private fun MessageBuilder.fill(content: String, files: List<BugFile>) {
        this.content = DiscordMessages.truncate(content, CONTENT_LIMIT)
        allowedMentions { +AllowedMentionType.UserMentions }
        files.forEach { file -> addFile(file.name, ChannelProvider(file.bytes.size.toLong()) { ByteReadChannel(file.bytes) }) }
    }

    companion object {
        /** Discord's message length limit. */
        private const val CONTENT_LIMIT = 2000

        /** Discord's maximum number of tags per forum thread. */
        private const val MAX_TAGS = 5

        /**
         * Converts a Kord message event in a thread into a [BugMessage].
         *
         * @param event the Kord event.
         * @return the message, or `null` when it was not sent in a thread or has no author.
         */
        suspend fun messageOf(event: MessageCreateEvent): BugMessage? {
            val message = event.message
            val author = message.author ?: return null
            val thread = message.getChannelOrNull() as? ThreadChannel ?: return null
            return BugMessage(
                threadId = thread.id.toString(),
                messageId = message.id.toString(),
                parentId = thread.parentId.toString(),
                threadName = thread.name,
                appliedTags = thread.appliedTags.map { it.toString() },
                authorId = author.id.toString(),
                authorName = author.username,
                authorIsBot = author.isBot,
                content = message.content,
                files = message.attachments.map { BugMessage.RemoteFile(it.filename, it.contentType, it.url, it.size) },
            )
        }
    }
}
