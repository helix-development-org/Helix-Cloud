package de.tytoss.iguard.packet

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.DiggingAction
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientPluginMessage
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerAbilities
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook
import de.tytoss.iguard.check.CheckEngine
import de.tytoss.iguard.model.AttackFrame
import de.tytoss.iguard.model.BlockAction
import de.tytoss.iguard.model.BlockActionFrame
import de.tytoss.iguard.model.ClientAbilitiesFrame
import de.tytoss.iguard.model.ClientAction
import de.tytoss.iguard.model.ClientActionFrame
import de.tytoss.iguard.model.ClientIdentityFrame
import de.tytoss.iguard.model.ClientTickFrame
import de.tytoss.iguard.model.InventoryClickFrame
import de.tytoss.iguard.model.MovementFrame
import de.tytoss.iguard.model.PacketFrame
import de.tytoss.iguard.model.ResetFrame
import de.tytoss.iguard.model.ServerAbilitiesFrame
import de.tytoss.iguard.model.ServerTeleportFrame
import de.tytoss.iguard.model.ServerVelocityFrame
import de.tytoss.iguard.model.SwingFrame
import de.tytoss.iguard.model.TeleportConfirmFrame
import de.tytoss.iguard.model.TimelineFrame
import de.tytoss.iguard.model.TimelineKind
import de.tytoss.iguard.model.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** PacketEvents listener translating client/server packets into [PacketFrame]s for the [CheckEngine]. */
class IGuardPacketListener(private val engine: CheckEngine) : PacketListenerAbstract(PacketListenerPriority.NORMAL) {
    private val sequences = ConcurrentHashMap<UUID, AtomicLong>()

    override fun onPacketReceive(event: PacketReceiveEvent) {
        val playerId = event.user.uuid ?: return
        val now = System.currentTimeMillis()
        val version = event.user.clientVersion.name
        val frame = when {
            event.packetType == PacketType.Configuration.Client.PLUGIN_MESSAGE -> identity(
                playerId, now, version,
                WrapperConfigClientPluginMessage(event).channelName,
                WrapperConfigClientPluginMessage(event).data,
            )
            event.packetType == PacketType.Play.Client.PLUGIN_MESSAGE -> identity(
                playerId, now, version,
                WrapperPlayClientPluginMessage(event).channelName,
                WrapperPlayClientPluginMessage(event).data,
            )
            event.packetType == PacketType.Play.Client.CLIENT_TICK_END -> ClientTickFrame(playerId, nextSequence(playerId), now, version)
            WrapperPlayClientPlayerFlying.isFlying(event.packetType) -> movement(event, playerId, now, version)
            event.packetType == PacketType.Play.Client.INTERACT_ENTITY -> attack(event, playerId, now, version)
            event.packetType == PacketType.Play.Client.ANIMATION -> SwingFrame(playerId, nextSequence(playerId), now, version)
            event.packetType == PacketType.Play.Client.ENTITY_ACTION -> action(event, playerId, now, version)
            event.packetType == PacketType.Play.Client.PLAYER_ABILITIES -> abilities(event, playerId, now, version)
            event.packetType == PacketType.Play.Client.TELEPORT_CONFIRM -> teleportConfirm(event, playerId, now, version)
            event.packetType == PacketType.Play.Client.PONG -> TimelineFrame(playerId, nextSequence(playerId), now, version, TimelineKind.CLIENT_PONG, WrapperPlayClientPong(event).id.toLong())
            event.packetType == PacketType.Play.Client.KEEP_ALIVE -> TimelineFrame(playerId, nextSequence(playerId), now, version, TimelineKind.CLIENT_KEEP_ALIVE, WrapperPlayClientKeepAlive(event).id)
            event.packetType == PacketType.Play.Client.PLAYER_DIGGING -> digging(event, playerId, now, version)
            event.packetType == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT -> placement(event, playerId, now, version)
            event.packetType == PacketType.Play.Client.USE_ITEM -> useItem(event, playerId, now, version)
            event.packetType == PacketType.Play.Client.CLICK_WINDOW -> inventoryClick(event, playerId, now, version)
            else -> null
        }
        frame?.let(engine::submit)
    }

    private fun identity(playerId: UUID, now: Long, version: String, channel: String, payload: ByteArray): ClientIdentityFrame {
        return ClientIdentityFrame(
            playerId,
            nextSequence(playerId),
            now,
            version,
            channel.take(128),
            payload.copyOfRange(0, minOf(payload.size, 4096)),
        )
    }

    override fun onPacketSend(event: PacketSendEvent) {
        val playerId = event.user.uuid ?: return
        val now = System.currentTimeMillis()
        val version = event.user.clientVersion.name
        val frame = when (event.packetType) {
            PacketType.Play.Server.ENTITY_VELOCITY -> velocity(event, playerId, now, version)
            PacketType.Play.Server.EXPLOSION -> explosion(event, playerId, now, version)
            PacketType.Play.Server.PLAYER_POSITION_AND_LOOK -> teleport(event, playerId, now, version)
            PacketType.Play.Server.PLAYER_ABILITIES -> serverAbilities(event, playerId, now, version)
            PacketType.Play.Server.RESPAWN -> ResetFrame(playerId, nextSequence(playerId), now, version, "respawn-packet")
            PacketType.Play.Server.JOIN_GAME -> ResetFrame(playerId, nextSequence(playerId), now, version, "join-packet")
            PacketType.Play.Server.PING -> TimelineFrame(playerId, nextSequence(playerId), now, version, TimelineKind.SERVER_PING, WrapperPlayServerPing(event).id.toLong())
            PacketType.Play.Server.KEEP_ALIVE -> TimelineFrame(playerId, nextSequence(playerId), now, version, TimelineKind.SERVER_KEEP_ALIVE, WrapperPlayServerKeepAlive(event).id)
            else -> null
        }
        frame?.let(engine::submit)
    }

    /** Drops the player's sequence counter and engine state (on quit). */
    fun remove(playerId: UUID) {
        sequences.remove(playerId)
        engine.remove(playerId)
    }

    private fun movement(event: PacketReceiveEvent, playerId: UUID, now: Long, version: String): MovementFrame {
        val wrapper = WrapperPlayClientPlayerFlying(event)
        val location = wrapper.location
        return MovementFrame(
            playerId,
            nextSequence(playerId),
            now,
            version,
            wrapper.hasPositionChanged(),
            wrapper.hasRotationChanged(),
            Vec3(location.x, location.y, location.z),
            location.yaw,
            location.pitch,
            wrapper.isOnGround,
        )
    }

    private fun attack(event: PacketReceiveEvent, playerId: UUID, now: Long, version: String): PacketFrame? {
        val wrapper = WrapperPlayClientInteractEntity(event)
        if (wrapper.action != WrapperPlayClientInteractEntity.InteractAction.ATTACK) return null
        return AttackFrame(playerId, nextSequence(playerId), now, version, wrapper.entityId)
    }

    private fun action(event: PacketReceiveEvent, playerId: UUID, now: Long, version: String): ClientActionFrame {
        val action = when (WrapperPlayClientEntityAction(event).action) {
            WrapperPlayClientEntityAction.Action.START_SPRINTING -> ClientAction.START_SPRINTING
            WrapperPlayClientEntityAction.Action.STOP_SPRINTING -> ClientAction.STOP_SPRINTING
            WrapperPlayClientEntityAction.Action.START_SNEAKING -> ClientAction.START_SNEAKING
            WrapperPlayClientEntityAction.Action.STOP_SNEAKING -> ClientAction.STOP_SNEAKING
            WrapperPlayClientEntityAction.Action.START_FLYING_WITH_ELYTRA -> ClientAction.START_ELYTRA
            else -> ClientAction.OTHER
        }
        return ClientActionFrame(playerId, nextSequence(playerId), now, version, action)
    }

    private fun abilities(event: PacketReceiveEvent, playerId: UUID, now: Long, version: String): ClientAbilitiesFrame {
        return ClientAbilitiesFrame(playerId, nextSequence(playerId), now, version, WrapperPlayClientPlayerAbilities(event).isFlying)
    }

    private fun teleportConfirm(event: PacketReceiveEvent, playerId: UUID, now: Long, version: String): TeleportConfirmFrame {
        return TeleportConfirmFrame(playerId, nextSequence(playerId), now, version, WrapperPlayClientTeleportConfirm(event).teleportId)
    }

    private fun digging(event: PacketReceiveEvent, playerId: UUID, now: Long, version: String): BlockActionFrame? {
        val wrapper = WrapperPlayClientPlayerDigging(event)
        val action = when (wrapper.action) {
            DiggingAction.START_DIGGING -> BlockAction.START_DIG
            DiggingAction.CANCELLED_DIGGING -> BlockAction.CANCEL_DIG
            DiggingAction.FINISHED_DIGGING -> BlockAction.FINISH_DIG
            else -> return null
        }
        val pos = wrapper.blockPosition
        return BlockActionFrame(playerId, nextSequence(playerId), now, version, action, pos.x, pos.y, pos.z, wrapper.blockFace.name, wrapper.sequence)
    }

    private fun placement(event: PacketReceiveEvent, playerId: UUID, now: Long, version: String): BlockActionFrame {
        val wrapper = WrapperPlayClientPlayerBlockPlacement(event)
        val pos = wrapper.blockPosition
        val cursor = wrapper.cursorPosition
        return BlockActionFrame(
            playerId, nextSequence(playerId), now, version, BlockAction.PLACE,
            pos.x, pos.y, pos.z, wrapper.face.name, wrapper.sequence, cursor.x, cursor.y, cursor.z,
        )
    }

    private fun useItem(event: PacketReceiveEvent, playerId: UUID, now: Long, version: String): BlockActionFrame {
        val wrapper = WrapperPlayClientUseItem(event)
        return BlockActionFrame(playerId, nextSequence(playerId), now, version, BlockAction.USE_ITEM, 0, 0, 0, wrapper.hand.name, wrapper.sequence)
    }

    private fun inventoryClick(event: PacketReceiveEvent, playerId: UUID, now: Long, version: String): InventoryClickFrame {
        val wrapper = WrapperPlayClientClickWindow(event)
        return InventoryClickFrame(
            playerId, nextSequence(playerId), now, version, wrapper.windowId, wrapper.stateId.orElse(null),
            wrapper.slot, wrapper.button, wrapper.windowClickType.name,
        )
    }

    private fun velocity(event: PacketSendEvent, playerId: UUID, now: Long, version: String): PacketFrame? {
        val wrapper = WrapperPlayServerEntityVelocity(event)
        if (wrapper.entityId != event.user.entityId) return null
        return ServerVelocityFrame(playerId, nextSequence(playerId), now, version, wrapper.velocity.toVec3(), "entity-velocity")
    }

    private fun explosion(event: PacketSendEvent, playerId: UUID, now: Long, version: String): ServerVelocityFrame {
        val velocity = WrapperPlayServerExplosion(event).knockback?.toVec3() ?: Vec3(0.0, 0.0, 0.0)
        return ServerVelocityFrame(playerId, nextSequence(playerId), now, version, velocity, "explosion")
    }

    private fun teleport(event: PacketSendEvent, playerId: UUID, now: Long, version: String): ServerTeleportFrame {
        val wrapper = WrapperPlayServerPlayerPositionAndLook(event)
        return ServerTeleportFrame(
            playerId,
            nextSequence(playerId),
            now,
            version,
            wrapper.teleportId,
            wrapper.position.toVec3(),
            wrapper.deltaMovement.toVec3(),
            wrapper.yaw,
            wrapper.pitch,
            wrapper.isRelativeFlag(RelativeFlag.X),
            wrapper.isRelativeFlag(RelativeFlag.Y),
            wrapper.isRelativeFlag(RelativeFlag.Z),
            wrapper.isRelativeFlag(RelativeFlag.YAW),
            wrapper.isRelativeFlag(RelativeFlag.PITCH),
        )
    }

    private fun serverAbilities(event: PacketSendEvent, playerId: UUID, now: Long, version: String): ServerAbilitiesFrame {
        val wrapper = WrapperPlayServerPlayerAbilities(event)
        return ServerAbilitiesFrame(
            playerId,
            nextSequence(playerId),
            now,
            version,
            wrapper.isFlying,
            wrapper.isFlightAllowed,
            wrapper.isInCreativeMode,
            wrapper.fovModifier,
        )
    }

    private fun nextSequence(playerId: UUID) = sequences.computeIfAbsent(playerId) { AtomicLong() }.incrementAndGet()
}

private fun Vector3d.toVec3() = Vec3(x, y, z)
