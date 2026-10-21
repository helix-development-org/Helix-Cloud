package org.helix.addons.guardlab.paper

import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.block.data.BlockData
import org.bukkit.generator.ChunkGenerator
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.Random

/** Empty chunks: the lab world consists only of the stations. */
class VoidGenerator : ChunkGenerator() {
    /** No terrain noise. */
    override fun shouldGenerateNoise(): Boolean = false

    /** No surface. */
    override fun shouldGenerateSurface(): Boolean = false

    /** No caves. */
    override fun shouldGenerateCaves(): Boolean = false

    /** No decorations. */
    override fun shouldGenerateDecorations(): Boolean = false

    /** No mobs. */
    override fun shouldGenerateMobs(): Boolean = false

    /** No structures. */
    override fun shouldGenerateStructures(): Boolean = false

    /** Spawn on the flat station. */
    override fun getFixedSpawnLocation(world: World, random: Random): Location = Location(world, 0.5, Stations.FEET_Y.toDouble(), -5.5)
}

/**
 * The GuardLab world `guardlab`: void world, frozen time and weather, no mob spawning, stations from
 * [Stations]. The stations are (re)built one per tick when the stored layout version differs or on demand.
 *
 * @property plugin the plugin (scheduler, data folder).
 */
class LabWorld(private val plugin: JavaPlugin) {
    /** The world, available after [load]. */
    lateinit var world: World
        private set

    /** Whether no station build is pending. */
    var ready: Boolean = false
        private set

    private val versionFile = File(plugin.dataFolder, "world.version")
    private val dataCache = HashMap<String, BlockData>()

    /**
     * Loads or creates the world and builds the stations if needed.
     *
     * @param force rebuild even when the stored version matches.
     */
    fun load(force: Boolean = false) {
        world = Bukkit.getWorld(NAME) ?: WorldCreator(NAME)
            .generator(VoidGenerator())
            .environment(World.Environment.NORMAL)
            .generateStructures(false)
            .createWorld() ?: error("world $NAME could not be created")
        world.difficulty = Difficulty.NORMAL
        world.time = NOON
        world.setStorm(false)
        world.isThundering = false
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false)
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false)
        world.setGameRule(GameRule.DO_FIRE_TICK, false)
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0)
        world.setGameRule(GameRule.KEEP_INVENTORY, true)
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true)
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false)
        world.setGameRule(GameRule.FALL_DAMAGE, true)
        val stored = runCatching { versionFile.readText().trim().toInt() }.getOrNull()
        if (force || stored != Stations.VERSION) buildAll() else ready = true
    }

    /** Rebuilds every station (one per tick). */
    fun buildAll() {
        ready = false
        val queue = ArrayDeque(Stations.ALL.map { it.name })
        plugin.logger.info("GuardLab: baue ${queue.size} Stationen …")
        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            val next = queue.removeFirstOrNull()
            if (next == null) {
                task.cancel()
                plugin.dataFolder.mkdirs()
                versionFile.writeText(Stations.VERSION.toString())
                ready = true
                plugin.logger.info("GuardLab: Testwelt fertig (Layout v${Stations.VERSION})")
                return@runTaskTimer
            }
            rebuild(next, clear = true)
        }, 1L, 1L)
    }

    /**
     * Rebuilds one station synchronously (build/break areas after a scenario).
     *
     * @param station station name.
     * @param clear resets the station's bounding box to air first.
     */
    fun rebuild(station: String, clear: Boolean = true) {
        val bounds = Stations.bounds(station) ?: return
        for (cx in (bounds[0] shr 4)..(bounds[3] shr 4)) {
            for (cz in (bounds[2] shr 4)..(bounds[5] shr 4)) world.getChunkAt(cx, cz).load(true)
        }
        Stations.build(station, sink(), clear)
    }

    /**
     * Converts an absolute lab position to a location.
     *
     * @param pos the position.
     * @return the location in the lab world.
     */
    fun location(pos: LabPos): Location = Location(world, pos.x, pos.y, pos.z, pos.yaw, pos.pitch)

    /**
     * Sets a block with physics (piston pulses).
     *
     * @param x absolute x.
     * @param y absolute y.
     * @param z absolute z.
     * @param data block data.
     */
    fun setWithPhysics(x: Int, y: Int, z: Int, data: String) {
        world.getBlockAt(x, y, z).setBlockData(blockData(data), true)
    }

    private fun sink() = BlockSink { x, y, z, data ->
        val block = world.getBlockAt(x, y, z)
        val target = blockData(data)
        if (block.blockData != target) block.setBlockData(target, false)
    }

    private fun blockData(data: String): BlockData = dataCache.getOrPut(data) { Bukkit.createBlockData(data) }

    /** World constants. */
    companion object {
        /** World name. */
        const val NAME = "guardlab"

        private const val NOON = 6000L
    }
}
