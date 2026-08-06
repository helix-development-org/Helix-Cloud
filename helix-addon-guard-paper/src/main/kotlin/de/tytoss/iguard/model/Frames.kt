package de.tytoss.iguard.model

import java.util.UUID

/** Main-thread snapshot of a player's world context (position, collisions, attributes, TPS/ping). */
data class EnvironmentFrame(
    val capturedAt: Long,
    val tick: Long,
    val worldId: UUID,
    val position: Vec3,
    val yaw: Float,
    val pitch: Float,
    val entityBox: Box,
    val collisionBoxes: List<Box>,
    val supportingCollision: Boolean,
    val colliding: Boolean,
    val gameMode: String,
    val surface: String,
    val surfaceSlipperiness: Double,
    val walkSpeed: Float,
    val movementSpeed: Double,
    val jumpStrength: Double,
    val gravity: Double,
    val stepHeight: Double,
    val eyeHeight: Double,
    val speedAmplifier: Int,
    val jumpAmplifier: Int,
    val ping: Int,
    val tps: Double,
    val velocity: Vec3,
    val exemptEnvironment: Boolean,
    val environmentTags: Set<String>,
    val chunkLoaded: Boolean,
)

sealed interface PacketFrame {
    val playerId: UUID
    val sequence: Long
    val receivedAt: Long
    val clientVersion: String
}

/** Client position/rotation packet (flying / position / look). */
data class MovementFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val positionChanged: Boolean,
    val rotationChanged: Boolean,
    val position: Vec3,
    val yaw: Float,
    val pitch: Float,
    val onGround: Boolean,
) : PacketFrame

/** Client attack (interact-entity) packet against a target entity. */
data class AttackFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val targetEntityId: Int,
) : PacketFrame

/** Arm-swing / animation packet — its absence around an attack is a killaura tell (combat.noswing). */
data class SwingFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
) : PacketFrame

/** Client entity-action kinds IGuard tracks (sprint/sneak/elytra state changes). */
enum class ClientAction {
    START_SPRINTING,
    STOP_SPRINTING,
    START_SNEAKING,
    STOP_SNEAKING,
    START_ELYTRA,
    OTHER,
}

/** Client entity-action packet (see [ClientAction]). */
data class ClientActionFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val action: ClientAction,
) : PacketFrame

/** Client abilities packet (the client toggling its fly state). */
data class ClientAbilitiesFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val flying: Boolean,
) : PacketFrame

/** Client tick-end marker packet, used by the timer check to measure the client's tick rate. */
data class ClientTickFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
) : PacketFrame

/** Plugin-message payload (brand / channel registrations) used for client fingerprinting. */
data class ClientIdentityFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val channel: String,
    val payload: ByteArray,
) : PacketFrame

/** Ping/keep-alive traffic directions used to reconstruct the latency timeline. */
enum class TimelineKind { SERVER_PING, CLIENT_PONG, SERVER_KEEP_ALIVE, CLIENT_KEEP_ALIVE }

/** One ping/keep-alive event (see [TimelineKind]). */
data class TimelineFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val kind: TimelineKind,
    val id: Long,
) : PacketFrame

/** Block interaction kinds observed from digging/placement packets. */
enum class BlockAction { START_DIG, CANCEL_DIG, FINISH_DIG, PLACE, USE_ITEM }

/** Client block dig/place/use packet with the targeted block and cursor position. */
data class BlockActionFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val action: BlockAction,
    val blockX: Int,
    val blockY: Int,
    val blockZ: Int,
    val face: String,
    val interactionSequence: Int,
    val cursorX: Float = 0.5f,
    val cursorY: Float = 0.5f,
    val cursorZ: Float = 0.5f,
) : PacketFrame

/** Client inventory click packet (window/slot/button), feeding the inventory checks. */
data class InventoryClickFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val windowId: Int,
    val stateId: Int?,
    val slot: Int,
    val button: Int,
    val clickType: String,
) : PacketFrame

/** Server-sent knockback/velocity, opening a grace window for the velocity checks. */
data class ServerVelocityFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val velocity: Vec3,
    val source: String,
) : PacketFrame

/** Server-sent teleport (position sync) with its relative flags and confirm id. */
data class ServerTeleportFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val teleportId: Int,
    val position: Vec3,
    val deltaMovement: Vec3,
    val yaw: Float,
    val pitch: Float,
    val relativeX: Boolean,
    val relativeY: Boolean,
    val relativeZ: Boolean,
    val relativeYaw: Boolean,
    val relativePitch: Boolean,
) : PacketFrame

/** Client confirmation of a server teleport, closing the pending-teleport window. */
data class TeleportConfirmFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val teleportId: Int,
) : PacketFrame

/** Server-sent abilities update (flight allowed, creative, movement scale). */
data class ServerAbilitiesFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val flying: Boolean,
    val flightAllowed: Boolean,
    val creative: Boolean,
    val movementScale: Float,
) : PacketFrame

/** Synthetic frame that resets a player's per-check state (respawn, world change, ...). */
data class ResetFrame(
    override val playerId: UUID,
    override val sequence: Long,
    override val receivedAt: Long,
    override val clientVersion: String,
    val reason: String,
) : PacketFrame

/** A player's identity plus the current and recent [EnvironmentFrame]s the workers read from. */
data class PlayerView(
    val playerId: UUID,
    val playerName: String,
    val entityId: Int,
    val current: EnvironmentFrame,
    val history: List<EnvironmentFrame>,
)

/** Last known legitimate position, used as the setback target. */
data class SafePosition(val worldId: UUID, val position: Vec3, val yaw: Float, val pitch: Float, val tick: Long)

/** One persisted check failure (VL, world context, evidence map, incident linkage). */
data class ViolationRecord(
    val createdAt: Long,
    val serverId: String,
    val playerId: UUID,
    val playerName: String,
    val checkId: String,
    val violationLevel: Double,
    val worldId: UUID,
    val position: Vec3,
    val ping: Int,
    val tps: Double,
    val evidence: Map<String, Any>,
    val incidentId: UUID? = null,
    val confidence: Double = 0.0,
    val shadowAction: String? = null,
)
