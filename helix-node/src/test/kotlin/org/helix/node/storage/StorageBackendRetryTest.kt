package org.helix.node.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StorageBackendRetryTest {
    @Test
    fun `retries with exponential backoff until the connection succeeds`() {
        var attempts = 0
        val sleeps = mutableListOf<Long>()

        val result = StorageBackend.connectWithRetry("Test", "test://host", sleep = { sleeps += it }) {
            attempts++
            if (attempts < 3) error("not up yet") else "connected"
        }

        assertEquals("connected", result)
        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }

    @Test
    fun `backoff never exceeds the configured cap`() {
        var attempts = 0
        val sleeps = mutableListOf<Long>()

        val result = StorageBackend.connectWithRetry("Test", "test://host", sleep = { sleeps += it }) {
            attempts++
            if (attempts < 8) error("still down") else "connected"
        }

        assertEquals("connected", result)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), sleeps)
    }

    @Test
    fun `gives up and rethrows after the max attempts`() {
        var attempts = 0

        assertFailsWith<IllegalStateException> {
            StorageBackend.connectWithRetry("Test", "test://host", sleep = {}) {
                attempts++
                error("permanently down")
            }
        }

        assertEquals(10, attempts)
    }

    @Test
    fun `succeeds immediately without sleeping when the first attempt works`() {
        val sleeps = mutableListOf<Long>()

        val result = StorageBackend.connectWithRetry("Test", "test://host", sleep = { sleeps += it }) { "connected" }

        assertEquals("connected", result)
        assertEquals(emptyList(), sleeps)
    }
}
