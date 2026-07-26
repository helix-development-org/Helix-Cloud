package de.tytoss.iguard.model

import java.time.Instant
import java.util.UUID

/** Independent evidence categories; multi-family corroboration raises incident confidence. */
enum class EvidenceFamily { CLIENT, PROTOCOL, MOVEMENT, COMBAT, WORLD }

/** Read model of an incident (case) as shown in commands, the panel and query results. */
data class IncidentSnapshot(
    val incidentId: UUID,
    val playerId: UUID,
    val playerName: String,
    val serverId: String,
    val openedAt: Instant,
    val updatedAt: Instant,
    val confidence: Double,
    val calibrated: Boolean,
    val families: Set<EvidenceFamily>,
    val evidenceCount: Int,
    val shadowAction: String?,
    val recipeVersion: String
)

/** Write model of an incident upsert as persisted through the store. */
data class IncidentRecord(
    val incidentId: UUID,
    val openedAt: Long,
    val updatedAt: Long,
    val serverId: String,
    val playerId: UUID,
    val playerName: String,
    val confidence: Double,
    val calibrated: Boolean,
    val families: Set<EvidenceFamily>,
    val evidenceCount: Int,
    val shadowAction: String?,
    val recipeVersion: String
)

/** Compressed movement-timeline payload recorded around an incident. */
data class ReplayRecord(
    val incidentId: UUID,
    val capturedAt: Long,
    val windowStart: Long,
    val windowEnd: Long,
    val compression: String,
    val payload: ByteArray,
    val truncated: Boolean,
    val expiresAt: Long
)

/** Replay listing entry (window + size) without the payload itself. */
data class ReplayMetadata(
    val incidentId: UUID,
    val capturedAt: Instant,
    val windowStart: Instant,
    val windowEnd: Instant,
    val compressedBytes: Int,
    val truncated: Boolean
)

/** Event for the proxy outbox (shadow alerts, sanction broadcasts); a no-op in Helix deployments. */
data class OutboxEvent(
    val eventId: UUID,
    val schemaVersion: Int,
    val occurredAt: Long,
    val serverId: String,
    val playerId: UUID,
    val playerName: String,
    val incidentId: UUID?,
    val type: String,
    val payload: Map<String, Any>
)

/** A sanction decision (shadow or enforced) taken for an incident. */
data class SanctionRecord(
    val sanctionId: UUID,
    val incidentId: UUID,
    val playerId: UUID,
    val action: String,
    val shadow: Boolean,
    val createdAt: Long,
    val expiresAt: Long?,
    val reason: String
)
