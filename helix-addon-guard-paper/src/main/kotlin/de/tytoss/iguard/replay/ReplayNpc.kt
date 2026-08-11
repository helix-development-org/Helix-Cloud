package de.tytoss.iguard.replay

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import org.bukkit.entity.Player
import java.util.EnumSet
import java.util.Optional
import java.util.UUID

/**
 * A packet-only player NPC that replays the recorded player's movements with their real skin and name.
 * It exists solely in the watching admin's client (all packets are unicast to that viewer), so it never
 * touches server entity state, collides with players, or shows up for anyone else. The skin comes from
 * a [UserProfile] carrying the recorded player's texture properties.
 */
class ReplayNpc(
    private val viewer: Player,
    private val profile: UserProfile,
) {
    val entityId: Int = SpigotReflectionUtil.generateEntityId()
    private val pm get() = PacketEvents.getAPI().playerManager

    /** Spawns the NPC at the given position for the viewer (tab-list entry first so the skin loads). */
    fun spawn(x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        // The tab-list entry must reach the client before the entity spawns or the skin won't resolve;
        // UPDATE_LISTED(false) keeps the NPC out of the visible player list.
        val info = WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            profile, false, 0, GameMode.SURVIVAL, null, null,
        )
        send(
            WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(
                    WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                ),
                info,
            ),
        )
        send(
            WrapperPlayServerSpawnEntity(
                entityId, Optional.of(profile.uuid), EntityTypes.PLAYER,
                Vector3d(x, y, z), pitch, yaw, yaw, 0, Optional.of(Vector3d(0.0, 0.0, 0.0)),
            ),
        )
        send(WrapperPlayServerEntityHeadLook(entityId, yaw))
    }

    /** Teleports the NPC to the next replay frame (position + look + head yaw). */
    fun move(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, onGround: Boolean) {
        send(WrapperPlayServerEntityTeleport(entityId, Location(Vector3d(x, y, z), yaw, pitch), onGround))
        send(WrapperPlayServerEntityRotation(entityId, yaw, pitch, onGround))
        send(WrapperPlayServerEntityHeadLook(entityId, yaw))
    }

    /** Destroys the NPC entity and removes its tab-list entry. */
    fun despawn() {
        send(WrapperPlayServerDestroyEntities(entityId))
        send(WrapperPlayServerPlayerInfoRemove(profile.uuid))
    }

    private fun send(packet: PacketWrapper<*>) {
        if (viewer.isOnline) runCatching { pm.sendPacket(viewer, packet) }
    }

    companion object {
        /**
         * Builds a profile with the recorded player's name + skin. Prefers a live profile if the player
         * is online, else fetches the texture from Mojang by UUID, else falls back to a nameless skin.
         * The NPC gets a fresh random UUID so it never clashes with the real player in the tab list.
         */
        fun profileFor(playerId: UUID, playerName: String): UserProfile {
            val npcId = UUID.randomUUID()
            val name = playerName.take(16)
            val texture = liveTexture(playerId) ?: mojangTexture(playerId)
            return UserProfile(npcId, name, texture?.let { listOf(it) } ?: emptyList())
        }

        private fun liveTexture(playerId: UUID): TextureProperty? {
            val online = org.bukkit.Bukkit.getPlayer(playerId) ?: return null
            val prop = online.playerProfile.properties.firstOrNull { it.name == "textures" } ?: return null
            return TextureProperty("textures", prop.value, prop.signature ?: "")
        }

        private fun mojangTexture(playerId: UUID): TextureProperty? = runCatching {
            val id = playerId.toString().replace("-", "")
            val url = java.net.URI("https://sessionserver.mojang.com/session/minecraft/profile/$id?unsigned=false").toURL()
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 3000
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // properties:[{"name":"textures","value":"...","signature":"..."}]
            val value = Regex(""""name"\s*:\s*"textures"\s*,\s*"value"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: return null
            val signature = Regex(""""signature"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
            TextureProperty("textures", value, signature)
        }.getOrNull()
    }
}
