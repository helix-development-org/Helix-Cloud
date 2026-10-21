package de.tytoss.iguard.api

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * An exemption grant that did not originate from the check engine itself (sampler events, the API,
 * the admin panel). Trace recordings log these so a replay can re-apply them.
 *
 * @property playerId exempted player.
 * @property grantedAt epoch millis of the grant.
 * @property millis length of the exemption.
 * @property reason grant reason.
 * @property scope which checks the exemption suspends.
 */
data class ExemptionGrant(
    val playerId: UUID,
    val grantedAt: Long,
    val millis: Long,
    val reason: String,
    val scope: IGuardExemptionScope,
)

/**
 * Thread-safe registry of temporary per-player check exemptions.
 *
 * Each player holds at most one exemption per [IGuardExemptionScope] (longest expiry wins within a
 * scope). Movement checks are suspended by either scope, combat/world/inventory checks only by
 * [IGuardExemptionScope.ALL] — knockback or a teleport must never switch off reach or killaura checks.
 *
 * @property clock epoch millis source used when the caller passes no explicit timestamp.
 */
class ExemptionManager(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Key(val playerId: UUID, val scope: IGuardExemptionScope)

    private data class Entry(val id: Long, val expiresAtMillis: Long, val reason: String)

    private val entries = ConcurrentHashMap<Key, Entry>()
    private val ids = AtomicLong()

    /** Observer of external grants (see [ExemptionGrant]); used by the trace recorder. */
    @Volatile var grantListener: ((ExemptionGrant) -> Unit)? = null

    /**
     * Exempts [playerId] from every check for [duration] (an external grant).
     *
     * @param playerId player to exempt.
     * @param duration positive length.
     * @param reason non-blank reason.
     * @return the exemption handle.
     */
    fun exempt(playerId: UUID, duration: Duration, reason: String): IGuardExemption =
        exempt(playerId, duration, reason, IGuardExemptionScope.ALL)

    /**
     * Exempts [playerId] from the checks of [scope] for [duration]; a shorter grant never shortens
     * an existing one of the same scope.
     *
     * @param playerId player to exempt.
     * @param duration positive length.
     * @param reason non-blank reason.
     * @param scope which checks to suspend.
     * @param now grant timestamp (epoch millis); the check engine passes the frame time.
     * @param external whether the grant comes from outside the check engine (reported to [grantListener]).
     * @return the exemption handle.
     */
    fun exempt(
        playerId: UUID,
        duration: Duration,
        reason: String,
        scope: IGuardExemptionScope,
        now: Long = clock(),
        external: Boolean = true,
    ): IGuardExemption {
        require(!duration.isNegative && !duration.isZero) { "duration must be positive" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        val millis = duration.toMillis().coerceAtLeast(1)
        val entry = Entry(ids.incrementAndGet(), now + millis, reason)
        val key = Key(playerId, scope)
        entries.compute(key) { _, current ->
            if (current == null || current.expiresAtMillis <= entry.expiresAtMillis) entry else current
        }
        if (external) grantListener?.invoke(ExemptionGrant(playerId, now, millis, reason, scope))
        return Handle(key, entry)
    }

    /** True while any exemption (movement or full) is active — movement checks are suspended. */
    fun isExempt(playerId: UUID, now: Long = clock()): Boolean =
        active(Key(playerId, IGuardExemptionScope.ALL), now) != null ||
            active(Key(playerId, IGuardExemptionScope.MOVEMENT), now) != null

    /** True while a full ([IGuardExemptionScope.ALL]) exemption is active — every check is suspended. */
    fun isFullyExempt(playerId: UUID, now: Long = clock()): Boolean =
        active(Key(playerId, IGuardExemptionScope.ALL), now) != null

    /**
     * The active exemptions as `scope:reason` labels (for traces and debug output).
     *
     * @param playerId player to inspect.
     * @param now reference time.
     * @return labels, full exemption first; empty when none is active.
     */
    fun activeReasons(playerId: UUID, now: Long = clock()): List<String> = buildList {
        active(Key(playerId, IGuardExemptionScope.ALL), now)?.let { add("all:${it.reason}") }
        active(Key(playerId, IGuardExemptionScope.MOVEMENT), now)?.let { add("movement:${it.reason}") }
    }

    /** When the longest active exemption ends, or null when none is active. */
    fun expiresAt(playerId: UUID, now: Long = clock()): Instant? =
        IGuardExemptionScope.entries
            .mapNotNull { active(Key(playerId, it), now)?.expiresAtMillis }
            .maxOrNull()
            ?.let(Instant::ofEpochMilli)

    /** Drops every exemption of the player (used on quit). */
    fun remove(playerId: UUID) {
        IGuardExemptionScope.entries.forEach { entries.remove(Key(playerId, it)) }
    }

    private fun active(key: Key, now: Long): Entry? {
        val entry = entries[key] ?: return null
        if (entry.expiresAtMillis > now) return entry
        entries.remove(key, entry)
        return null
    }

    private inner class Handle(
        private val key: Key,
        private val entry: Entry,
    ) : IGuardExemption {
        private val active = AtomicBoolean(true)
        override val playerId: UUID = key.playerId
        override val reason = entry.reason
        override val expiresAt: Instant = Instant.ofEpochMilli(entry.expiresAtMillis)
        override val scope: IGuardExemptionScope = key.scope

        override fun cancel(): Boolean {
            if (!active.compareAndSet(true, false)) return false
            return entries.remove(key, entry)
        }
    }
}
