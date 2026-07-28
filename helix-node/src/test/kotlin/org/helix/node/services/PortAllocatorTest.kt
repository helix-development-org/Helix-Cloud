package org.helix.node.services

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PortAllocatorTest {
    @Test
    fun `skips ports already claimed by other helix services`() {
        val allocator = PortAllocator(canBind = { true })

        assertEquals(30002, allocator.allocate(30000, setOf(30000, 30001)))
    }

    @Test
    fun `skips ports the OS refuses to bind even when not claimed by helix`() {
        val allocator = PortAllocator(canBind = { port -> port != 30000 })

        assertEquals(30001, allocator.allocate(30000, emptySet()))
    }

    @Test
    fun `throws when no port is free in range`() {
        val allocator = PortAllocator(canBind = { false })

        assertFailsWith<IllegalStateException> { allocator.allocate(65534, emptySet()) }
    }

    @Test
    fun `real bind probe skips a port actually held by another socket`() {
        val held = ServerSocket(0)
        try {
            val allocator = PortAllocator()

            val allocated = allocator.allocate(held.localPort, emptySet())

            assertEquals(held.localPort + 1, allocated)
            // the allocator's own probe socket was closed again — confirm the returned port is bindable
            ServerSocket(allocated).close()
        } finally {
            held.close()
        }
    }
}
