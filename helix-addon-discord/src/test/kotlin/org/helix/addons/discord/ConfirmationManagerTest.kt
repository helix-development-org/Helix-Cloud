package org.helix.addons.discord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfirmationManagerTest {
    private var now = 0L
    private val manager = ConfirmationManager(timeoutMs = { 30_000L }, now = { now })

    private fun pending(tier: ActionTier = ActionTier.DESTRUCTIVE): PendingConfirmation =
        manager.create("42", "Steve", "service.stop", listOf("Lobby-1"), tier, "Lobby-1")

    @Test
    fun `destructive confirm is single use and initiator only`() {
        val entry = pending()

        assertIs<ConfirmOutcome.WrongUser>(manager.confirm(entry.id, "43"))
        val ready = assertIs<ConfirmOutcome.Ready>(manager.confirm(entry.id, "42"))
        assertEquals("service.stop", ready.pending.action)
        assertIs<ConfirmOutcome.NotFound>(manager.confirm(entry.id, "42"))
    }

    @Test
    fun `confirmations expire`() {
        val entry = pending()
        now += 30_001L

        assertIs<ConfirmOutcome.Expired>(manager.confirm(entry.id, "42"))
        assertIs<ConfirmOutcome.NotFound>(manager.confirm(entry.id, "42"))
    }

    @Test
    fun `critical requires the exact typed text and aborts on mismatch`() {
        val wrong = pending(ActionTier.CRITICAL)
        assertIs<ConfirmOutcome.TextMismatch>(manager.confirm(wrong.id, "42", "lobby-1"))
        assertIs<ConfirmOutcome.NotFound>(manager.confirm(wrong.id, "42", "Lobby-1"))

        val right = pending(ActionTier.CRITICAL)
        assertIs<ConfirmOutcome.Ready>(manager.confirm(right.id, "42", " Lobby-1 "))
    }

    @Test
    fun `cancel is initiator only and prune returns expired entries`() {
        val entry = pending()
        assertIs<ConfirmOutcome.WrongUser>(manager.cancel(entry.id, "43"))
        assertIs<ConfirmOutcome.Cancelled>(manager.cancel(entry.id, "42"))

        pending()
        now += 30_001L
        val expired = manager.prune()
        assertEquals(1, expired.size)
        assertTrue(manager.prune().isEmpty())
    }
}
