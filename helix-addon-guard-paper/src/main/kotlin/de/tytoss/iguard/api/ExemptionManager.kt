package de.tytoss.iguard.api

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Thread-safe registry of temporary per-player check exemptions (longest expiry wins). */
class ExemptionManager {
    private data class Entry(val id: Long, val expiresAtMillis: Long, val reason: String)

    private val entries = ConcurrentHashMap<UUID, Entry>()
    private val ids = AtomicLong()

    /** Whitelists [playerId] for [duration]; a shorter grant never shortens an existing one. */
    fun exempt(playerId: UUID, duration: Duration, reason: String): IGuardExemption {
        require(!duration.isNegative && !duration.isZero) { "duration must be positive" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        val expires = System.currentTimeMillis() + duration.toMillis().coerceAtLeast(1)
        val entry = Entry(ids.incrementAndGet(), expires, reason)
        entries.compute(playerId) { _, current ->
            if (current == null || current.expiresAtMillis <= expires) entry else current
        }
        return Handle(playerId, entry)
    }

    /** True while an unexpired exemption exists (expired entries are pruned lazily). */
    fun isExempt(playerId: UUID, now: Long = System.currentTimeMillis()): Boolean {
        val entry = entries[playerId] ?: return false
        if (entry.expiresAtMillis > now) return true
        entries.remove(playerId, entry)
        return false
    }

    /** When the active exemption ends, or null when none is active. */
    fun expiresAt(playerId: UUID): Instant? {
        val entry = entries[playerId] ?: return null
        return if (isExempt(playerId)) Instant.ofEpochMilli(entry.expiresAtMillis) else null
    }

    /** Drops any exemption for the player (used on quit). */
    fun remove(playerId: UUID) {
        entries.remove(playerId)
    }

    private inner class Handle(
        override val playerId: UUID,
        private val entry: Entry,
    ) : IGuardExemption {
        private val active = AtomicBoolean(true)
        override val reason = entry.reason
        override val expiresAt = Instant.ofEpochMilli(entry.expiresAtMillis)

        override fun cancel(): Boolean {
            if (!active.compareAndSet(true, false)) return false
            return entries.remove(playerId, entry)
        }
    }
}
