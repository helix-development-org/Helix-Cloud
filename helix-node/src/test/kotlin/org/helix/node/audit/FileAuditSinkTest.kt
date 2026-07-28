package org.helix.node.audit

import java.nio.file.Files
import kotlin.io.path.appendText
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.api.audit.AuditEntry

class FileAuditSinkTest {
    private val file = createTempDirectory("audit").resolve("audit.jsonl")

    @Test
    fun `appends and reloads the tail in order`() {
        val sink = FileAuditSink(file)
        (1..5).forEach { i -> sink.append(AuditEntry(i.toLong(), "node", "system", "entry-$i", "ok")) }

        val recent = sink.loadRecent(3)

        assertEquals(listOf("entry-3", "entry-4", "entry-5"), recent.map { it.summary })
    }

    @Test
    fun `loadRecent tolerates a single torn last line instead of returning nothing`() {
        val sink = FileAuditSink(file)
        sink.append(AuditEntry(1, "node", "system", "entry-1", "ok"))
        sink.append(AuditEntry(2, "node", "system", "entry-2", "ok"))
        // simulate a crash mid-write: a truncated, non-JSON final line
        file.appendText("{\"epochMs\":3,\"category\":\"node\",\"summary\":\"tor")

        val recent = sink.loadRecent(10)

        assertEquals(listOf("entry-1", "entry-2"), recent.map { it.summary })
    }

    @Test
    fun `rotates past the configured size threshold and keeps writing to a fresh file`() {
        val sink = FileAuditSink(file, maxFileSizeBytes = 80)
        (1..20).forEach { i -> sink.append(AuditEntry(i.toLong(), "node", "system", "entry-$i", "ok")) }

        val siblings = file.parent.listDirectoryEntries("${file.fileName}.*")
        assertTrue(siblings.isNotEmpty(), "expected at least one rolled file")
        assertTrue(Files.exists(file), "current file must still exist after rotation")
        assertTrue(Files.size(file) < 80 * 4, "current file should not have re-accumulated the whole history")

        // the most recent entries are still readable from the current (rotated-into) file
        val recent = sink.loadRecent(1)
        assertEquals("entry-20", recent.single().summary)
    }

    @Test
    fun `reads a large tail without loading the whole file`() {
        val sink = FileAuditSink(file)
        (1..500).forEach { i -> sink.append(AuditEntry(i.toLong(), "node", "system", "entry-$i", "ok")) }

        val recent = sink.loadRecent(5)

        assertEquals(listOf("entry-496", "entry-497", "entry-498", "entry-499", "entry-500"), recent.map { it.summary })
    }
}
