package de.tytoss.iguard.setback

import de.tytoss.iguard.model.SafePosition
import de.tytoss.iguard.snapshot.SnapshotStore
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.BoundingBox
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/** Conservative, rate-limited teleports back to the last safe position after a hard check failure. */
class SetbackService(
    private val plugin: JavaPlugin,
    private val snapshots: SnapshotStore
) {
    private val lastSetback = ConcurrentHashMap<UUID, Long>()

    /** Requests a setback to [safe]; skipped when recent, stale, cross-world or not collision-free. */
    fun request(playerId: UUID, safe: SafePosition, reason: String) {
        val now = System.currentTimeMillis()
        val previous = lastSetback.putIfAbsent(playerId, now)
        if (previous != null && now - previous < 1000) return
        lastSetback[playerId] = now
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val player = Bukkit.getPlayer(playerId) ?: return@Runnable
            val current = snapshots.view(playerId)?.current ?: return@Runnable
            if (current.tick - safe.tick !in 0..20 || current.worldId != safe.worldId) return@Runnable
            val world = Bukkit.getWorld(safe.worldId) ?: return@Runnable
            val width = current.entityBox.maxX - current.entityBox.minX
            val height = current.entityBox.maxY - current.entityBox.minY
            if (!isCollisionFree(world, safe, width, height)) return@Runnable
            player.teleport(Location(world, safe.position.x, safe.position.y, safe.position.z, safe.yaw, safe.pitch))
            plugin.logger.fine("Set back ${player.name} after $reason")
        })
    }

    private fun isCollisionFree(world: World, safe: SafePosition, width: Double, height: Double): Boolean {
        val half = width / 2.0 - 0.01
        val box = BoundingBox(
            safe.position.x - half,
            safe.position.y + 0.01,
            safe.position.z - half,
            safe.position.x + half,
            safe.position.y + height - 0.01,
            safe.position.z + half
        )
        for (x in floor(box.minX).toInt()..floor(box.maxX).toInt()) {
            for (z in floor(box.minZ).toInt()..floor(box.maxZ).toInt()) {
                if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
                for (y in floor(box.minY).toInt()..floor(box.maxY).toInt()) {
                    if (world.getBlockAt(x, y, z).collisionShape.overlaps(box)) return false
                }
            }
        }
        return true
    }
}
