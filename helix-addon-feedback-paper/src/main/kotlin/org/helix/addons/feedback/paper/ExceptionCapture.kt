package org.helix.addons.feedback.paper

import com.destroystokyo.paper.event.server.ServerExceptionEvent
import com.destroystokyo.paper.exception.ServerPluginException
import kotlinx.serialization.json.JsonObject
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Turns unhandled exceptions of this server into automatic reports (`feedback.auto`): Paper's
 * [ServerExceptionEvent] (plugin, event, command and scheduler exceptions) and every `SEVERE` log record with a
 * throwable. The same stack signature is sent at most once per minute; the node deduplicates further by hash.
 *
 * @property send sends one report off the main thread: hash, title, description, context.
 * @property context supplies the server context (called on the thread the exception happened on).
 */
class ExceptionCapture(
    private val send: (hash: String, title: String, description: String, context: JsonObject?) -> Unit,
    private val context: () -> JsonObject?,
) : Listener {
    private val recent = RecentHashes()
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            val error = record.thrown ?: return
            if (record.level.intValue() < Level.SEVERE.intValue()) return
            report(error, "log: ${record.loggerName ?: "?"} – ${record.message ?: ""}".take(200))
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    /** Hooks into the root logger (call once on enable). */
    fun install() {
        Logger.getLogger("").addHandler(handler)
    }

    /** Removes the log hook (call on disable). */
    fun uninstall() {
        Logger.getLogger("").removeHandler(handler)
    }

    /** Paper reports exceptions of plugins, events, commands and tasks here. */
    @EventHandler
    fun onServerException(event: ServerExceptionEvent) {
        val exception = event.exception
        val origin = (exception as? ServerPluginException)?.responsiblePlugin?.name?.let { "plugin: $it" } ?: "server"
        report(exception.cause ?: exception, origin)
    }

    /**
     * Reports one throwable if its signature was not sent within the last minute.
     *
     * @param error the throwable.
     * @param origin where it was caught.
     */
    fun report(error: Throwable, origin: String) {
        if (isOwn(error)) return
        val hash = StackTraces.hash(error)
        if (!recent.admit(hash)) return
        val ctx = runCatching { context() }.getOrNull()
        send(hash, StackTraces.title(error), StackTraces.describe(error, origin), ctx)
    }

    /** Never report failures of the reporting path itself (would loop when the node is down). */
    private fun isOwn(error: Throwable): Boolean =
        error.stackTrace.take(5).any { it.className.startsWith(ExceptionCapture::class.java.name) }
}
