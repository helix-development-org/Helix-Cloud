package org.helix.addons.discord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.message.MapMessages

class AuditLogTest {
    private var config = DiscordConfig(
        auditChannelId = "audit",
        auditChannels = mapOf("link" to "links"),
    )
    private val sent = mutableListOf<Pair<String, String>>()
    private val descriptors = mutableMapOf(
        "kick" to ActionDescriptor("kick", "kicks", "kick <player>", playerCommand = true),
        "service.stop" to ActionDescriptor("service.stop", "stops", "service.stop <service>"),
        "discord.config.set" to ActionDescriptor("discord.config.set", "config", "discord.config.set <kv>"),
    )
    private val audit = AuditLog(
        config = { config },
        texts = DiscordMessages(
            MapMessages(
                mapOf(
                    "audit.actor.panel" to "Panel",
                    "audit.action" to "{icon} {action} {args} - {actor} - {source}",
                    "audit.denied" to "denied {user} {action} {reason}",
                    "audit.confirmation.cancelled" to "cancelled {user} {action} {args}",
                    "audit.link.created" to "linked {player} {discord} {discordId} {via}",
                ),
            ),
        ),
        descriptorOf = { descriptors[it] },
        sink = { channel, text -> sent += channel to text },
    )

    @Test
    fun `cli and rest invocations are logged with their actor`() {
        audit.observe(
            ActionInvocation("service.stop", listOf("Lobby-1"), ActionSource.CLI),
            ActionResult.ok("stopping"),
        )
        audit.observe(
            ActionInvocation("service.stop", listOf("Lobby-1"), ActionSource.REST, actor = "Tytoss"),
            ActionResult.error("nope"),
        )

        assertEquals(2, sent.size)
        assertEquals("audit", sent[0].first)
        assertEquals("✅ service.stop `Lobby-1` - CLI - CLI", sent[0].second)
        assertEquals("❌ service.stop `Lobby-1` - Tytoss - Panel", sent[1].second)
    }

    @Test
    fun `bridge invocations are logged only for player commands and attributed to the player`() {
        audit.observe(
            ActionInvocation("kick", listOf("Tytoss", "Griefer"), ActionSource.BRIDGE),
            ActionResult.ok(),
        )
        audit.observe(
            ActionInvocation("service.stop", listOf("Lobby-1"), ActionSource.BRIDGE),
            ActionResult.ok(),
        )

        assertEquals(1, sent.size)
        assertEquals("✅ kick `Griefer` - Tytoss - Ingame", sent.single().second)
    }

    @Test
    fun `system and addon invocations stay out`() {
        audit.observe(ActionInvocation("service.stop", emptyList(), ActionSource.SYSTEM), ActionResult.ok())
        audit.observe(ActionInvocation("service.stop", emptyList(), ActionSource.ADDON), ActionResult.ok())

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `secret arguments are masked`() {
        audit.observe(
            ActionInvocation("discord.config.set", listOf("token=abc"), ActionSource.CLI),
            ActionResult.ok(),
        )

        assertTrue(sent.single().second.contains("•••"))
        assertTrue(!sent.single().second.contains("abc"))
    }

    @Test
    fun `event types route to their configured channels and blank channels drop`() {
        audit.link("created", DiscordLink("42", "steve#dc", "uuid-1", "Steve", 0L, "game-code"), "game-code")
        assertEquals("links", sent.single().first)

        sent.clear()
        config = DiscordConfig()
        audit.denied("steve#dc", "service.stop", "node")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `discord executions and confirmations are logged`() {
        audit.discordAction("steve#dc", "Steve", "service.stop", listOf("Lobby-1"), ActionResult.ok())
        audit.confirmation(
            "cancelled",
            PendingConfirmation("1", "42", "Steve", "service.stop", listOf("Lobby-1"), ActionTier.DESTRUCTIVE, "x", 0L),
            "steve#dc",
        )

        assertEquals("✅ service.stop `Lobby-1` - Steve (steve#dc) - Discord", sent[0].second)
        assertEquals("cancelled Steve (steve#dc) service.stop `Lobby-1`", sent[1].second)
    }
}
