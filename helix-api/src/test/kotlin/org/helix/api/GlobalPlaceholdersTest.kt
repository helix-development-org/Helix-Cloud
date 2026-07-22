package org.helix.api

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.helix.api.message.GlobalPlaceholders
import org.helix.api.message.LegacyToMini
import org.helix.api.message.MapMessages

class GlobalPlaceholdersTest {
    @AfterTest
    fun cleanup() {
        GlobalPlaceholders.set("prefix", "")
    }

    @Test
    fun `global prefix applies to every formatted message`() {
        GlobalPlaceholders.set("prefix", "&bHelix &8»")
        val messages = MapMessages(mapOf("greet" to "{prefix} &fHello {name}!"))

        assertEquals("&bHelix &8» &fHello Steve!", messages.format("greet", "name" to "Steve"))
    }

    @Test
    fun `message-specific parameters win over globals`() {
        GlobalPlaceholders.set("prefix", "GLOBAL")
        val messages = MapMessages(mapOf("chat" to "{prefix} {message}"))

        assertEquals("LOCAL hi", messages.format("chat", "prefix" to "LOCAL", "message" to "hi"))
    }

    @Test
    fun `legacy codes translate to minimessage tags`() {
        assertEquals("<gold><bold>Hi</bold>", LegacyToMini.translate("&6&lHi</bold>"))
        assertEquals("plain & text", LegacyToMini.translate("plain & text"))
    }
}
