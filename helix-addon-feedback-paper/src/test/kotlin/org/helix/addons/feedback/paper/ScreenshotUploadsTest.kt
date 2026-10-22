package org.helix.addons.feedback.paper

import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenshotUploadsTest {
    private val player = UUID.randomUUID()
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `assembles chunks in index order`() {
        val uploads = ScreenshotUploads()
        val upload = uploads.start(player, 7, 1000, 0)
        assertIs<ScreenshotUploads.Outcome.Stored>(uploads.chunk(upload.id, player, 1, 2, b64(byteArrayOf(3, 4))))
        assertIs<ScreenshotUploads.Outcome.Stored>(uploads.chunk(upload.id, player, 0, 2, b64(byteArrayOf(1, 2))))
        val done = assertIs<ScreenshotUploads.Outcome.Complete>(uploads.finish(upload.id, player))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), done.bytes)
        assertEquals(7, done.upload.nr)
    }

    @Test
    fun `missing chunk makes the upload incomplete`() {
        val uploads = ScreenshotUploads()
        val upload = uploads.start(player, 1, 0, 0)
        uploads.chunk(upload.id, player, 0, 2, b64(byteArrayOf(1)))
        assertEquals("incomplete", assertIs<ScreenshotUploads.Outcome.Failed>(uploads.finish(upload.id, player)).reason)
    }

    @Test
    fun `size limit rejects the upload`() {
        val uploads = ScreenshotUploads()
        val upload = uploads.start(player, 1, 3, 0)
        val failed = assertIs<ScreenshotUploads.Outcome.Failed>(uploads.chunk(upload.id, player, 0, 1, b64(ByteArray(4))))
        assertEquals("too_large", failed.reason)
        assertEquals("unknown", assertIs<ScreenshotUploads.Outcome.Failed>(uploads.finish(upload.id, player)).reason)
    }

    @Test
    fun `chunks from another player or with bad data are refused`() {
        val uploads = ScreenshotUploads()
        val upload = uploads.start(player, 1, 0, 0)
        val other = assertIs<ScreenshotUploads.Outcome.Failed>(uploads.chunk(upload.id, UUID.randomUUID(), 0, 1, b64(byteArrayOf(1))))
        assertNull(other.upload)
        assertEquals("invalid", assertIs<ScreenshotUploads.Outcome.Failed>(uploads.chunk(upload.id, player, 0, 1, "%%%")).reason)
    }

    @Test
    fun `uploads expire after the timeout`() {
        val uploads = ScreenshotUploads(timeoutMs = 20_000)
        val old = uploads.start(player, 1, 0, 0)
        val fresh = uploads.start(player, 2, 0, 15_000)
        val expired = uploads.expire(20_500)
        assertEquals(listOf(old.id), expired.map { it.id })
        assertIs<ScreenshotUploads.Outcome.Stored>(uploads.chunk(fresh.id, player, 0, 1, b64(byteArrayOf(9))))
    }

    @Test
    fun `parts split data for the node and round-trip`() {
        val data = ByteArray(10) { it.toByte() }
        val parts = ScreenshotUploads.parts(data, 4)
        assertEquals(3, parts.size)
        val joined = parts.map { Base64.getDecoder().decode(it) }.reduce { a, b -> a + b }
        assertContentEquals(data, joined)
        assertTrue(ScreenshotUploads.parts(ByteArray(0)).size == 1)
    }
}
