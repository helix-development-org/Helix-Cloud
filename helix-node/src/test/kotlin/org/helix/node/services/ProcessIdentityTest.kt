package org.helix.node.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessIdentityTest {
    private val handle = ProcessHandle.current()
    private val actualStart = handle.info().startInstant().get().toEpochMilli()

    @Test
    fun `a matching start instant confirms the process survived`() {
        assertTrue(ProcessIdentity.survived(handle, actualStart))
    }

    @Test
    fun `a start instant mismatch (reused pid) is treated as not survived`() {
        assertFalse(ProcessIdentity.survived(handle, actualStart + 60_000))
    }

    @Test
    fun `a missing persisted start instant is treated as not survived`() {
        assertFalse(ProcessIdentity.survived(handle, null))
    }

    @Test
    fun `a dead process is never treated as survived, even with a matching instant`() {
        val process = ProcessBuilder("true").start()
        process.waitFor()
        val deadHandle = process.toHandle()

        assertFalse(ProcessIdentity.survived(deadHandle, actualStart))
    }
}
