package org.helix.addons.chat

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.helix.addon.sdk.testing.RecordingAddonContext

class PrettyChatAddonTest {
    private val context = RecordingAddonContext(createTempDirectory("chat"))
    private val addon = PrettyChatAddon().also { it.onEnable(context) }

    @Test
    fun `format is published and updatable`() {
        assertTrue(context.bridgeValues["chat.format"]!!.contains("{message}"))

        assertTrue(context.run("chat.format", "{name}:", "{message}").success)
        assertEquals("{name}: {message}", context.bridgeValues["chat.format"])
        assertFalse(context.run("chat.format", "kaputt").success)
    }

    @Test
    fun `default format renders the suffix component`() {
        assertTrue(context.bridgeValues["chat.format"]!!.contains("{suffix}"), "clan tags render via {suffix}")
    }

    @Test
    fun `legacy formats without suffix are migrated on enable`() {
        val storage = org.helix.api.storage.InMemoryAddonStorage()
        storage.write("chat", """{"format":"{prefix}{color}{name} &8» &f{message}","rules":[]}""")
        val migratedContext = RecordingAddonContext(createTempDirectory("chat-migrate"), storage)
        PrettyChatAddon().onEnable(migratedContext)

        assertTrue(migratedContext.bridgeValues["chat.format"]!!.contains("{name}{suffix}"))
        val persisted = kotlinx.serialization.json.Json.decodeFromString<ChatConfig>(storage.read("chat")!!)
        assertTrue(persisted.format.contains("{suffix}"), "migration is persisted")
    }

    @Test
    fun `prefix rules resolve through permissions in order`() {
        context.run("chat.prefix.add", "chat.rank.admin", "&c", "&cAdmin")
        context.run("chat.prefix.add", "chat.rank.vip", "&6", "&6VIP")
        context.permissionCheck = { player, permission ->
            player == "steve" && permission == "chat.rank.admin" ||
                player == "alex" && permission == "chat.rank.vip"
        }

        val resolver = context.displayResolvers.single()
        assertEquals("&cAdmin ", resolver.resolve("steve")?.prefix)
        assertEquals("&c", resolver.resolve("steve")?.color)
        assertEquals("&6VIP ", resolver.resolve("alex")?.prefix)
        assertNull(resolver.resolve("random"))
    }

    @Test
    fun `prefix rules can be listed and removed`() {
        context.run("chat.prefix.add", "chat.rank.vip", "&6", "&6VIP")

        assertTrue(context.run("chat.prefix.list").lines.single().contains("chat.rank.vip"))
        assertTrue(context.run("chat.prefix.remove", "chat.rank.vip").success)
        assertTrue(context.run("chat.prefix.list").lines.single().contains("no prefix rules"))
        assertFalse(context.run("chat.prefix.remove", "chat.rank.vip").success)
    }
}
