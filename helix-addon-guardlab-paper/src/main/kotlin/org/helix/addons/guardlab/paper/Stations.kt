package org.helix.addons.guardlab.paper

/** Receives the blocks of a station (Bukkit world in production, a map in tests). */
fun interface BlockSink {
    /**
     * Sets one block.
     *
     * @param x absolute x.
     * @param y absolute y.
     * @param z absolute z.
     * @param data block data string, e.g. `minecraft:ladder[facing=north]`.
     */
    fun set(x: Int, y: Int, z: Int, data: String)
}

/**
 * A named position inside a station, relative to its origin (block coordinates, feet level `y = 0`).
 *
 * @property x relative block x.
 * @property y relative feet level.
 * @property z relative block z.
 * @property yaw facing (0 = +z / south).
 * @property pitch pitch.
 * @property free skips the standability check (vehicle and liquid spots).
 */
data class Spot(val x: Int, val y: Int, val z: Int, val yaw: Float = 0f, val pitch: Float = 0f, val free: Boolean = false)

/**
 * An absolute position in the lab world.
 *
 * @property x block-centre x.
 * @property y feet y.
 * @property z block-centre z.
 * @property yaw facing.
 * @property pitch pitch.
 */
data class LabPos(val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

/**
 * One station of the lab world.
 *
 * @property name station id (first part of a spot reference).
 * @property spots spot name → spot.
 * @property length floor length along +z.
 * @property wallHeight height of the barrier fence around the floor (structures stay below
 *   `max(wallHeight, 20)`, the rebuild height).
 * @property pulses relative positions where the runner places a redstone block to fire a mechanism.
 * @property build places the station's blocks (after the default floor).
 */
class StationDef(
    val name: String,
    val spots: Map<String, Spot>,
    val length: Int = 50,
    val wallHeight: Int = 5,
    val pulses: List<Triple<Int, Int, Int>> = emptyList(),
    val build: StationBuilder.() -> Unit = {},
)

/**
 * Places blocks relative to a station origin; `y = 0` is the feet level, the floor is `y = -1`.
 *
 * @property originX absolute x of the station origin.
 * @property sink target.
 */
class StationBuilder(private val originX: Int, private val sink: BlockSink) {
    /**
     * Sets one block.
     *
     * @param x relative x.
     * @param y relative y.
     * @param z relative z.
     * @param data block data.
     */
    fun set(x: Int, y: Int, z: Int, data: String) = sink.set(originX + x, Stations.FEET_Y + y, z, data)

    /**
     * Fills a cuboid (inclusive corners, any order).
     *
     * @param x1 corner x.
     * @param y1 corner y.
     * @param z1 corner z.
     * @param x2 corner x.
     * @param y2 corner y.
     * @param z2 corner z.
     * @param data block data.
     */
    fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, data: String) {
        for (x in minOf(x1, x2)..maxOf(x1, x2)) {
            for (y in minOf(y1, y2)..maxOf(y1, y2)) {
                for (z in minOf(z1, z2)..maxOf(z1, z2)) set(x, y, z, data)
            }
        }
    }

    /**
     * Replaces the floor of a rectangle with a liquid/filler basin of [depth] blocks, walled and floored in stone.
     *
     * @param x1 corner x.
     * @param z1 corner z.
     * @param x2 corner x.
     * @param z2 corner z.
     * @param depth depth below the feet level.
     * @param data filler.
     */
    fun basin(x1: Int, z1: Int, x2: Int, z2: Int, depth: Int, data: String) {
        fill(minOf(x1, x2) - 1, -depth - 1, minOf(z1, z2) - 1, maxOf(x1, x2) + 1, -1, maxOf(z1, z2) + 1, Stations.STONE)
        fill(x1, -depth, z1, x2, -1, z2, data)
    }
}

/**
 * The deterministic layout of the GuardLab world: one station every [SPACING] blocks along +x, each with a
 * 41-block-wide stone floor at `y = 63` (feet level 64), fenced by barriers. Spots are referenced as
 * `<station>.<spot>` from the suite file. Bumping [VERSION] rebuilds the world on the next start.
 */
object Stations {
    /** Layout version; a different stored version triggers a rebuild. */
    const val VERSION = 1

    /** Distance between station origins along x. */
    const val SPACING = 64

    /** Feet level of every floor. */
    const val FEET_Y = 64

    /** Half width of every floor. */
    const val HALF_WIDTH = 20

    /** Stone. */
    const val STONE = "minecraft:stone"

    private const val AIR = "minecraft:air"
    private const val BARRIER = "minecraft:barrier"
    private const val FLOOR_START = -10
    private const val DEPTH = 7
    private const val CLEAR_HEIGHT = 20

    private fun top(def: StationDef) = maxOf(def.wallHeight, CLEAR_HEIGHT)

    /** All stations in index order (the order defines their position – append only). */
    val ALL: List<StationDef> = listOf(
        StationDef("flat", mapOf("start" to Spot(0, 0, 0), "wait" to Spot(0, 0, -6), "dummy" to Spot(6, 0, -6, yaw = 180f))),
        StationDef("parcours", mapOf("start" to Spot(0, 0, 0), "tunnel" to Spot(9, 0, -2))) {
            // Rising pillars with two-block gaps (sprint-jump course) …
            for (k in 0..7) fill(-1, 0, 3 + 4 * k, 1, k, 4 + 4 * k, STONE)
            // … and a two-block-high tunnel: jumping bumps the head every time.
            fill(7, 2, 0, 11, 2, 30, STONE)
        },
        StationDef(
            "ice",
            mapOf("ice" to Spot(-6, 0, 0), "packed" to Spot(0, 0, 0), "blue" to Spot(6, 0, 0), "tunnel" to Spot(12, 0, 0)),
        ) {
            fill(-7, -1, 0, -5, -1, 50, "minecraft:ice")
            fill(-1, -1, 0, 1, -1, 50, "minecraft:packed_ice")
            fill(5, -1, 0, 7, -1, 50, "minecraft:blue_ice")
            fill(11, -1, 0, 13, -1, 50, "minecraft:blue_ice")
            fill(11, 2, 2, 13, 2, 50, STONE)
        },
        StationDef(
            "surfaces",
            mapOf("slime" to Spot(-8, 0, 0), "honey" to Spot(-2, 0, 0), "soulsand" to Spot(4, 0, 0), "slimefall" to Spot(10, 10, -1)),
        ) {
            fill(-9, -1, 0, -7, -1, 40, "minecraft:slime_block")
            fill(-3, -1, 0, -1, -1, 40, "minecraft:honey_block")
            fill(3, -1, 0, 5, -1, 40, "minecraft:soul_sand")
            fill(9, 0, -2, 11, 9, 0, STONE)
            fill(8, -1, 1, 12, -1, 6, "minecraft:slime_block")
        },
        StationDef(
            "liquid",
            mapOf("pool" to Spot(0, 0, 0), "bubble" to Spot(14, 0, 6, free = true), "lava" to Spot(-14, 0, 2)),
        ) {
            basin(-8, 2, 8, 20, 3, "minecraft:water")
            basin(-16, 4, -12, 8, 2, "minecraft:lava")
            // Upward bubble column in a glass shaft, entered from inside.
            fill(13, -1, 5, 15, 9, 7, "minecraft:glass")
            set(14, -1, 6, "minecraft:soul_sand")
            fill(14, 0, 6, 14, 8, 6, "minecraft:bubble_column[drag=false]")
            set(14, 9, 6, AIR)
        },
        StationDef(
            "climb",
            mapOf("ladder" to Spot(-8, 0, 1), "vine" to Spot(-4, 0, 1), "scaffolding" to Spot(0, 0, 1), "wall" to Spot(6, 0, 1)),
        ) {
            fill(-12, 0, 3, 12, 11, 3, STONE)
            fill(-8, 0, 2, -8, 11, 2, "minecraft:ladder[facing=north]")
            fill(-4, 0, 2, -4, 11, 2, "minecraft:vine[south=true]")
            fill(0, 0, 2, 0, 11, 2, "minecraft:scaffolding[bottom=false,distance=0]")
        },
        StationDef(
            "steps",
            mapOf("stairs" to Spot(-8, 0, 0), "slabs" to Spot(0, 0, 0), "steplane" to Spot(8, 0, 0), "small" to Spot(14, 0, 0)),
        ) {
            for (k in 0..7) {
                if (k > 0) fill(-9, 0, 2 + k, -7, k - 1, 2 + k, STONE)
                fill(-9, k, 2 + k, -7, k, 2 + k, "minecraft:stone_stairs[facing=south,half=bottom,shape=straight]")
            }
            for (k in 0..9) {
                val y = k / 2
                if (y > 0) fill(-1, 0, 2 + k, 1, y - 1, 2 + k, STONE)
                fill(-1, y, 2 + k, 1, y, 2 + k, if (k % 2 == 0) "minecraft:stone_slab[type=bottom]" else "minecraft:stone_slab[type=top]")
            }
            for (k in 0..5) fill(7, 0, 3 + 3 * k, 9, k, 5 + 3 * k, STONE)
            for (z in 2..30) {
                when (z % 4) {
                    0 -> fill(13, 0, z, 15, 0, z, "minecraft:white_carpet")
                    1 -> fill(13, 0, z, 15, 0, z, "minecraft:snow[layers=4]")
                    2 -> fill(13, 0, z, 15, 0, z, "minecraft:stone_slab[type=bottom]")
                }
            }
        },
        StationDef(
            "launcher",
            mapOf("pad" to Spot(0, 1, 4), "drop" to Spot(10, 10, 0), "dropwater" to Spot(-10, 16, 0)),
            pulses = listOf(Triple(0, -2, 4)),
        ) {
            // Sticky piston under a slime block; a redstone block below the piston (pulse point) fires it.
            set(0, -1, 4, "minecraft:sticky_piston[extended=false,facing=up]")
            set(0, 0, 4, "minecraft:slime_block")
            fill(9, 0, -1, 11, 9, 1, STONE)
            fill(-11, 0, -1, -9, 15, 1, STONE)
            basin(-12, 2, -8, 6, 3, "minecraft:water")
        },
        StationDef(
            "hazard",
            mapOf("cobweb" to Spot(-4, 0, 0), "powdersnow" to Spot(4, 0, 0), "berries" to Spot(12, 0, 0)),
        ) {
            fill(-6, 0, 3, -2, 1, 10, "minecraft:cobweb")
            basin(2, 3, 6, 10, 2, "minecraft:powder_snow")
            fill(10, -1, 3, 14, -1, 10, "minecraft:grass_block[snowy=false]")
            fill(10, 0, 3, 14, 0, 10, "minecraft:sweet_berry_bush[age=3]")
        },
        StationDef("elytra", mapOf("top" to Spot(0, 40, 0)), length = 200, wallHeight = 45) {
            fill(-1, 0, -1, 1, 39, 1, STONE)
        },
        StationDef(
            "vehicles",
            mapOf(
                "rail" to Spot(-8, 0, 1, free = true),
                "boat" to Spot(1, 0, 1, free = true),
                "iceboat" to Spot(7, 0, 1),
                "horse" to Spot(16, 0, 1),
            ),
            length = 80,
        ) {
            for (z in 0..70) {
                if (z % 8 == 4) {
                    set(-8, -1, z, "minecraft:redstone_block")
                    set(-8, 0, z, "minecraft:powered_rail[powered=true,shape=north_south]")
                } else {
                    set(-8, 0, z, "minecraft:rail[shape=north_south]")
                }
            }
            set(-8, 0, 71, STONE)
            basin(0, 0, 2, 70, 1, "minecraft:water")
            fill(6, -1, 0, 8, -1, 70, "minecraft:blue_ice")
        },
        StationDef(
            "build",
            mapOf(
                "breakgrid" to Spot(-9, 0, 4),
                "placewall" to Spot(-16, 0, 8),
                "far" to Spot(-16, 0, 2),
                "bridge" to Spot(4, 0, 2, yaw = 180f, pitch = 78f),
                "scaffold" to Spot(12, 0, 1),
            ),
        ) {
            // The right half ends at z = 2: a gap over a catch floor five blocks lower (bridging, scaffold).
            fill(-1, -1, 3, HALF_WIDTH, -1, 50, AIR)
            fill(-1, -6, 3, HALF_WIDTH, -6, 50, STONE)
            fill(-12, 0, 6, -6, 2, 8, STONE)
            fill(-18, 0, 10, -14, 4, 10, STONE)
        },
        StationDef(
            "combat",
            mapOf(
                "player" to Spot(0, 0, 0),
                "mob" to Spot(0, 0, 2, yaw = 180f),
                "dummy" to Spot(0, 0, 3, yaw = 180f),
                "dummyfar" to Spot(0, 0, 5, yaw = 180f),
            ),
        ),
        StationDef("seat", mapOf("seat" to Spot(0, 0, 0))),
    )

    private val byName = ALL.withIndex().associate { (index, def) -> def.name to (index to def) }

    /**
     * Absolute x of a station origin.
     *
     * @param name station name.
     * @return the x, or null for an unknown station.
     */
    fun originX(name: String): Int? = byName[name]?.first?.times(SPACING)

    /**
     * Whether a `<station>.<spot>` reference exists.
     *
     * @param ref the reference.
     * @return true when known.
     */
    fun exists(ref: String): Boolean = resolve(ref) != null

    /**
     * Resolves a spot reference to an absolute position (block centre, feet level).
     *
     * @param ref `<station>.<spot>`.
     * @return the position, or null when unknown.
     */
    fun resolve(ref: String): LabPos? {
        val (station, spotName) = ref.split('.', limit = 2).takeIf { it.size == 2 } ?: return null
        val (index, def) = byName[station] ?: return null
        val spot = def.spots[spotName] ?: return null
        return LabPos(index * SPACING + spot.x + 0.5, (FEET_Y + spot.y).toDouble(), spot.z + 0.5, spot.yaw, spot.pitch)
    }

    /**
     * Absolute pulse points of a station.
     *
     * @param name station name.
     * @return the points (empty for unknown stations).
     */
    fun pulses(name: String): List<Triple<Int, Int, Int>> {
        val (index, def) = byName[name] ?: return emptyList()
        return def.pulses.map { (x, y, z) -> Triple(index * SPACING + x, FEET_Y + y, z) }
    }

    /**
     * Absolute bounding box of a station (floor, fence and everything above/below it).
     *
     * @param name station name.
     * @return `minX, minY, minZ, maxX, maxY, maxZ`, or null.
     */
    fun bounds(name: String): IntArray? {
        val (index, def) = byName[name] ?: return null
        val x = index * SPACING
        return intArrayOf(
            x - HALF_WIDTH - 1, FEET_Y - DEPTH - 1, FLOOR_START - 1,
            x + HALF_WIDTH + 1, FEET_Y + top(def), def.length + 1,
        )
    }

    /**
     * Builds one station: default floor and barrier fence, then its own structures.
     *
     * @param name station name.
     * @param sink target.
     * @param clear first resets the whole bounding box to air (rebuild after build/break scenarios).
     */
    fun build(name: String, sink: BlockSink, clear: Boolean = false) {
        val (index, def) = byName[name] ?: error("unknown station $name")
        val builder = StationBuilder(index * SPACING, sink)
        with(builder) {
            if (clear) fill(-HALF_WIDTH - 1, -DEPTH - 1, FLOOR_START - 1, HALF_WIDTH + 1, top(def), def.length + 1, AIR)
            fill(-HALF_WIDTH, -1, FLOOR_START, HALF_WIDTH, -1, def.length, STONE)
            fill(-HALF_WIDTH - 1, -DEPTH, FLOOR_START - 1, HALF_WIDTH + 1, def.wallHeight, FLOOR_START - 1, BARRIER)
            fill(-HALF_WIDTH - 1, -DEPTH, def.length + 1, HALF_WIDTH + 1, def.wallHeight, def.length + 1, BARRIER)
            fill(-HALF_WIDTH - 1, -DEPTH, FLOOR_START, -HALF_WIDTH - 1, def.wallHeight, def.length, BARRIER)
            fill(HALF_WIDTH + 1, -DEPTH, FLOOR_START, HALF_WIDTH + 1, def.wallHeight, def.length, BARRIER)
            def.build(this)
        }
    }

    /** Every spot reference (`<station>.<spot>`) in station order. */
    val spotRefs: List<String> get() = ALL.flatMap { def -> def.spots.keys.map { "${def.name}.$it" } }
}
