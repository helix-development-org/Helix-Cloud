package org.helix.node.display

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BridgeValueStoreTest {
    @Test
    fun `republishing a key under a different owner transfers ownership`() {
        val store = BridgeValueStore()
        store.publish("addon-a", "tablist.header", "from a")
        store.publish("addon-b", "tablist.header", "from b")

        assertEquals("from b", store.all()["tablist.header"])

        // addon-a no longer owns the key, so disabling it must not delete addon-b's live value
        store.unpublishOwner("addon-a")
        assertEquals("from b", store.all()["tablist.header"])

        store.unpublishOwner("addon-b")
        assertNull(store.all()["tablist.header"])
    }

    @Test
    fun `unpublish removes a single key only when owned by the caller`() {
        val store = BridgeValueStore()
        store.publish("addon-a", "chat.format", "value")

        store.unpublish("addon-b", "chat.format")
        assertEquals("value", store.all()["chat.format"], "non-owner unpublish must be a no-op")

        store.unpublish("addon-a", "chat.format")
        assertNull(store.all()["chat.format"])
    }
}
