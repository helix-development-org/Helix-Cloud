package org.helix.addons.discord

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * An action waiting for its second click.
 *
 * @property id opaque confirmation id, carried in the component custom id.
 * @property discordId the Discord user who initiated the action; only this
 *   user may confirm or cancel.
 * @property actorName the linked Minecraft name acting on behalf.
 * @property action the action to execute on confirmation.
 * @property arguments the action arguments.
 * @property tier the confirmation tier the action was classified into.
 * @property expectedText exact text a [ActionTier.CRITICAL] confirmation
 *   must type, usually the target argument or the action name.
 * @property createdAt epoch millis of creation.
 */
data class PendingConfirmation(
    val id: String,
    val discordId: String,
    val actorName: String,
    val action: String,
    val arguments: List<String>,
    val tier: ActionTier,
    val expectedText: String,
    val createdAt: Long,
)

/**
 * Outcome of resolving a pending confirmation.
 */
sealed class ConfirmOutcome {
    /**
     * The confirmation is valid; the pending action was consumed and must
     * be executed now.
     *
     * @property pending the confirmed action.
     */
    data class Ready(val pending: PendingConfirmation) : ConfirmOutcome()

    /** No pending confirmation with that id — unknown or already resolved. */
    data object NotFound : ConfirmOutcome()

    /**
     * The confirmation existed but ran out of time; it was removed.
     *
     * @property pending the expired action, for the audit trail.
     */
    data class Expired(val pending: PendingConfirmation) : ConfirmOutcome()

    /** A different Discord user tried to resolve the confirmation. */
    data object WrongUser : ConfirmOutcome()

    /**
     * The typed text of a critical confirmation did not match.
     *
     * @property pending the still-pending action.
     */
    data class TextMismatch(val pending: PendingConfirmation) : ConfirmOutcome()

    /**
     * The initiator cancelled the action.
     *
     * @property pending the cancelled action, for the audit trail.
     */
    data class Cancelled(val pending: PendingConfirmation) : ConfirmOutcome()
}

/**
 * Holds actions between the first and the second click.
 *
 * [ActionTier.DESTRUCTIVE] actions wait for their red confirm button,
 * [ActionTier.CRITICAL] actions for a modal in which the initiator types
 * [PendingConfirmation.expectedText] exactly. Confirmations expire after
 * the configured timeout and are only resolvable by their initiator.
 *
 * @property timeoutMs supplier of the confirmation lifetime in millis.
 * @property now clock, injectable for tests.
 */
class ConfirmationManager(
    private val timeoutMs: () -> Long = { 30_000L },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val pending = ConcurrentHashMap<String, PendingConfirmation>()
    private val counter = AtomicLong()

    /**
     * Registers an action awaiting confirmation.
     *
     * @param discordId initiating Discord user id.
     * @param actorName linked Minecraft name acting on behalf.
     * @param action the action to execute.
     * @param arguments the action arguments.
     * @param tier the confirmation tier.
     * @param expectedText text a critical confirmation must type.
     * @return the pending confirmation, carrying the id for the custom id.
     */
    fun create(
        discordId: String,
        actorName: String,
        action: String,
        arguments: List<String>,
        tier: ActionTier,
        expectedText: String,
    ): PendingConfirmation {
        prune()
        val entry = PendingConfirmation(
            id = "${counter.incrementAndGet()}",
            discordId = discordId,
            actorName = actorName,
            action = action,
            arguments = arguments,
            tier = tier,
            expectedText = expectedText,
            createdAt = now(),
        )
        pending[entry.id] = entry
        return entry
    }

    /**
     * Resolves a confirmation attempt.
     *
     * @param id the confirmation id from the custom id.
     * @param discordId the resolving Discord user.
     * @param typedText the typed text for critical confirmations; `null`
     *   for button confirmations of destructive actions.
     * @return the outcome; [ConfirmOutcome.Ready] consumes the entry.
     */
    fun confirm(id: String, discordId: String, typedText: String? = null): ConfirmOutcome {
        val entry = pending[id] ?: return ConfirmOutcome.NotFound
        if (expired(entry)) {
            pending.remove(id)
            return ConfirmOutcome.Expired(entry)
        }
        if (entry.discordId != discordId) {
            return ConfirmOutcome.WrongUser
        }
        if (entry.tier == ActionTier.CRITICAL && typedText?.trim() != entry.expectedText) {
            pending.remove(id)
            return ConfirmOutcome.TextMismatch(entry)
        }
        pending.remove(id)
        return ConfirmOutcome.Ready(entry)
    }

    /**
     * Cancels a pending confirmation.
     *
     * @param id the confirmation id.
     * @param discordId the cancelling Discord user; must be the initiator.
     * @return the outcome.
     */
    fun cancel(id: String, discordId: String): ConfirmOutcome {
        val entry = pending[id] ?: return ConfirmOutcome.NotFound
        if (entry.discordId != discordId) {
            return ConfirmOutcome.WrongUser
        }
        pending.remove(id)
        return if (expired(entry)) ConfirmOutcome.Expired(entry) else ConfirmOutcome.Cancelled(entry)
    }

    /**
     * Retrieves a pending confirmation without consuming it, for example
     * to open the critical-confirm modal.
     *
     * @param id the confirmation id.
     * @return the entry, or `null` when unknown or expired.
     */
    fun peek(id: String): PendingConfirmation? {
        val entry = pending[id] ?: return null
        if (expired(entry)) {
            pending.remove(id)
            return null
        }
        return entry
    }

    /**
     * Removes all expired confirmations.
     *
     * @return the removed entries, for the audit trail.
     */
    fun prune(): List<PendingConfirmation> {
        val expired = pending.values.filter(::expired)
        expired.forEach { pending.remove(it.id) }
        return expired
    }

    private fun expired(entry: PendingConfirmation): Boolean =
        now() - entry.createdAt > timeoutMs()
}
