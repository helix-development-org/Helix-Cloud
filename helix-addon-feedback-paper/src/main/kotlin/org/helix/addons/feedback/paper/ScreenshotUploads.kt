package org.helix.addons.feedback.paper

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

/**
 * Collects the screenshot chunks a LabyMod client sends on `helix:feedback` (pure logic, no Bukkit types).
 *
 * One upload per report: [start] registers it, [chunk] stores the Base64 pieces, [finish] checks completeness and the
 * size limit and returns the JPEG bytes. Uploads older than [timeoutMs] are dropped by [expire].
 *
 * @property timeoutMs how long an upload may take.
 */
class ScreenshotUploads(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    private val uploads = HashMap<String, Upload>()

    /**
     * One upload in progress.
     *
     * @property id upload id (sent to the client).
     * @property player the reporter.
     * @property nr report number the screenshot belongs to.
     * @property maxBytes size limit of the assembled image.
     * @property startedAt start time (epoch ms).
     */
    class Upload(val id: String, val player: UUID, val nr: Long, val maxBytes: Int, val startedAt: Long) {
        /** Received chunks by index. */
        val chunks: MutableMap<Int, ByteArray> = HashMap()

        /** Announced chunk count (0 = not yet known). */
        var count: Int = 0

        /** Bytes received so far. */
        var received: Int = 0
    }

    /** Result of [chunk] and [finish]. */
    sealed interface Outcome {
        /** The chunk was stored. */
        data object Stored : Outcome

        /**
         * The upload is complete.
         *
         * @property upload the finished upload.
         * @property bytes the assembled JPEG.
         */
        class Complete(val upload: Upload, val bytes: ByteArray) : Outcome

        /**
         * The upload failed and was removed.
         *
         * @property upload the failed upload, null when unknown.
         * @property reason `unknown`, `too_large`, `invalid`, `incomplete`.
         */
        class Failed(val upload: Upload?, val reason: String) : Outcome
    }

    /**
     * Registers a new upload.
     *
     * @param player the reporter.
     * @param nr report number.
     * @param maxBytes size limit.
     * @param now current time (epoch ms).
     * @return the upload.
     */
    @Synchronized
    fun start(player: UUID, nr: Long, maxBytes: Int, now: Long): Upload {
        val upload = Upload(UUID.randomUUID().toString(), player, nr, maxBytes, now)
        uploads[upload.id] = upload
        return upload
    }

    /**
     * Stores one chunk.
     *
     * @param id upload id.
     * @param sender the player sending it (must be the reporter).
     * @param index chunk index.
     * @param count announced chunk count.
     * @param base64 the chunk data.
     * @return [Outcome.Stored] or [Outcome.Failed].
     */
    @Synchronized
    fun chunk(id: String, sender: UUID, index: Int, count: Int, base64: String): Outcome {
        val upload = uploads[id]?.takeIf { it.player == sender } ?: return Outcome.Failed(null, "unknown")
        if (count <= 0 || count > MAX_CHUNKS || index !in 0 until count || (upload.count != 0 && upload.count != count)) {
            return fail(upload, "invalid")
        }
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return fail(upload, "invalid")
        upload.count = count
        upload.chunks.put(index, bytes)?.let { upload.received -= it.size }
        upload.received += bytes.size
        if (upload.maxBytes > 0 && upload.received > upload.maxBytes) return fail(upload, "too_large")
        return Outcome.Stored
    }

    /**
     * Completes an upload after the client's `done`.
     *
     * @param id upload id.
     * @param sender the player.
     * @return [Outcome.Complete] or [Outcome.Failed].
     */
    @Synchronized
    fun finish(id: String, sender: UUID): Outcome {
        val upload = uploads[id]?.takeIf { it.player == sender } ?: return Outcome.Failed(null, "unknown")
        uploads.remove(id)
        if (upload.count == 0 || upload.chunks.size != upload.count) return Outcome.Failed(upload, "incomplete")
        val out = ByteArrayOutputStream(upload.received)
        for (i in 0 until upload.count) out.write(upload.chunks.getValue(i))
        return Outcome.Complete(upload, out.toByteArray())
    }

    /**
     * Drops an upload (client error).
     *
     * @param id upload id.
     * @param sender the player.
     * @return the removed upload, null if unknown.
     */
    @Synchronized
    fun cancel(id: String, sender: UUID): Upload? = uploads[id]?.takeIf { it.player == sender }?.also { uploads.remove(id) }

    /**
     * Removes uploads that took longer than the timeout.
     *
     * @param now current time (epoch ms).
     * @return the expired uploads.
     */
    @Synchronized
    fun expire(now: Long): List<Upload> {
        val expired = uploads.values.filter { now - it.startedAt > timeoutMs }
        expired.forEach { uploads.remove(it.id) }
        return expired
    }

    /**
     * Drops all uploads of a player (quit).
     *
     * @param player the player.
     */
    @Synchronized
    fun forget(player: UUID) {
        uploads.values.removeIf { it.player == player }
    }

    private fun fail(upload: Upload, reason: String): Outcome {
        uploads.remove(upload.id)
        return Outcome.Failed(upload, reason)
    }

    /** Defaults. */
    companion object {
        /** Upload timeout: 20 s. */
        const val DEFAULT_TIMEOUT_MS: Long = 20_000

        /** Upper bound for the announced chunk count. */
        const val MAX_CHUNKS: Int = 512

        /** Fallback size limit when the node's settings are unknown (2 MiB). */
        const val DEFAULT_MAX_BYTES: Int = 2 * 1024 * 1024

        /**
         * Splits bytes into Base64 parts for `feedback.attach.chunk` calls.
         *
         * @param bytes the data.
         * @param partBytes raw bytes per part.
         * @return the Base64 parts (at least one).
         */
        fun parts(bytes: ByteArray, partBytes: Int = NODE_PART_BYTES): List<String> =
            (bytes.indices step partBytes).map { Base64.getEncoder().encodeToString(bytes.copyOfRange(it, minOf(bytes.size, it + partBytes))) }
                .ifEmpty { listOf("") }

        /** Raw bytes per node chunk call. */
        const val NODE_PART_BYTES: Int = 256 * 1024
    }
}
