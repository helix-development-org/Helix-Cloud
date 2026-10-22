package org.helix.addons.npc.paper.rig

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.DyedItemColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.helix.addons.npc.paper.api.RigHandle
import org.helix.addons.npc.paper.villager.Kinematics
import org.helix.addons.npc.paper.villager.PartTransform
import org.helix.addons.npc.paper.villager.RigSpec
import kotlin.math.abs

/**
 * A generic display-entity rig ([RigHandle]): one non-persistent [ItemDisplay] per model part (item model
 * `<namespace>:<modelId>/<part>`, transform `NONE`), posed by [RigPose].
 *
 * [tick] runs every server tick (Helix-NPC's rig timer): follows the attached entity, and every [UPDATE_TICKS] — while
 * a player is within [VIEW_RANGE] — moves the displays (teleport interpolation) and sends the changed part
 * transformations (transformation interpolation). Displays that vanish with their chunk are respawned once it is back.
 *
 * @property owner plugin owning the rig.
 * @param namespace item model namespace.
 * @param modelId item model path prefix.
 * @param spec the rig.
 * @param start initial feet position.
 */
class ModelRig(
    val owner: Plugin,
    private val namespace: String,
    private val modelId: String,
    spec: RigSpec,
    start: Location,
) : RigHandle {
    private val pose = RigPose(spec)
    private val modelParts = spec.parts.filter { it.model }.map { it.name }
    private val displays = LinkedHashMap<String, ItemDisplay>()
    private val sent = HashMap<String, PartTransform>()
    private val tints = HashMap<String, Int>()
    private val models = HashMap<String, String>()
    private var glow = -1
    private var brightness = -1
    private var current: Location = start.clone().apply { pitch = 0f }
    private var placed: Location? = null
    private var attached: Entity? = null
    private var removed = false
    private var firstSpawn = true
    private var ticks = 0L
    private var restart = false

    private val spawned: Boolean get() = displays.size == modelParts.size && displays.values.all { it.isValid }

    /**
     * Spawns the displays if the chunk is loaded. Idempotent. Called by [tick]: the first time on the tick after the
     * rig was created (so a caller's initial pose, scale and tint apply from the first frame), later once a second
     * while the displays are missing.
     */
    fun spawn() {
        if (removed || spawned) return
        despawn()
        if (!current.isWorldLoaded) return
        val world = current.world ?: return
        if (!world.isChunkLoaded(current.blockX shr 4, current.blockZ shr 4)) return
        val solved = pose.solve()
        val base = current.clone()
        for (part in modelParts) {
            displays[part] = world.spawn(base, ItemDisplay::class.java) { display ->
                display.isPersistent = false
                display.setItemStack(item(part))
                display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                display.interpolationDuration = INTERPOLATION_TICKS
                display.teleportDuration = UPDATE_TICKS.toInt()
                display.shadowRadius = 0f
                applyLook(display)
                solved[part]?.let {
                    display.transformation = DisplayPose.toBukkit(it)
                    sent[part] = it
                }
            }
        }
        placed = base
        firstSpawn = false
    }

    /**
     * Advances the rig by one server tick.
     *
     * @return `false` once the rig is gone for good (removed or its world unloaded).
     */
    fun tick(): Boolean {
        if (removed) return false
        if (!current.isWorldLoaded) {
            remove()
            return false
        }
        ticks++
        follow()
        if (!spawned) {
            if (firstSpawn || ticks % RESPAWN_CHECK_TICKS == 0L) spawn()
            return true
        }
        if (displays.values.first().world != current.world) {
            despawn()
            spawn()
            return true
        }
        if (ticks % UPDATE_TICKS != 0L || !viewed()) return true
        move()
        restart = !restart
        DisplayPose.send(pose.solve(), displays, sent, restart, INTERPOLATION_TICKS)
        return true
    }

    private fun follow() {
        val entity = attached ?: return
        if (!entity.isValid) {
            attached = null
            return
        }
        val loc = entity.location
        loc.yaw = (entity as? LivingEntity)?.bodyYaw ?: loc.yaw
        loc.pitch = 0f
        current = loc
    }

    private fun move() {
        val last = placed
        if (last != null && last.world == current.world && last.distanceSquared(current) < MOVE_EPSILON_SQ &&
            abs(last.yaw - current.yaw) < YAW_EPSILON
        ) {
            return
        }
        val target = current.clone()
        displays.values.forEach { it.teleport(target) }
        placed = target
    }

    private fun viewed(): Boolean {
        val world = current.world ?: return false
        return world.players.any { it.location.distanceSquared(current) <= VIEW_RANGE * VIEW_RANGE }
    }

    private fun item(part: String): ItemStack = ItemStack(Material.PAPER).apply {
        val key = models[part] ?: "$namespace:$modelId/$part"
        editMeta { it.itemModel = NamespacedKey.fromString(key) }
        tints[part]?.let { setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(Color.fromRGB(it and 0xFFFFFF))) }
    }

    private fun applyLook(display: ItemDisplay) {
        display.isGlowing = glow >= 0
        display.glowColorOverride = if (glow >= 0) Color.fromRGB(glow and 0xFFFFFF) else null
        display.brightness = if (brightness >= 0) Display.Brightness(brightness, brightness) else null
    }

    private fun refreshItem(part: String) {
        displays[part]?.takeIf { it.isValid }?.setItemStack(item(part))
    }

    private fun despawn() {
        displays.values.forEach { it.remove() }
        displays.clear()
        sent.clear()
        placed = null
    }

    override fun parts(): List<String> = pose.spec.parts.map { it.name }

    override fun height(): Double = pose.spec.height / Kinematics.PIXELS

    override fun setPartRotation(part: String, x: Double, y: Double, z: Double) = pose.rotate(part, x, y, z)

    override fun setPartOffset(part: String, x: Double, y: Double, z: Double) = pose.offset(part, x, y, z)

    override fun setPartScale(part: String, x: Double, y: Double, z: Double) = pose.scale(part, x, y, z)

    override fun resetPart(part: String) = pose.reset(part)

    override fun resetPose() = pose.resetAll()

    override fun setScale(scale: Double) {
        pose.scale = scale.coerceAtLeast(0.0)
    }

    override fun setTint(part: String, rgb: Int) {
        if (!pose.has(part)) return
        if (rgb < 0) tints.remove(part) else tints[part] = rgb and 0xFFFFFF
        refreshItem(part)
    }

    override fun setPartModel(part: String, itemModel: String?) {
        if (!pose.has(part)) return
        if (itemModel == null || NamespacedKey.fromString(itemModel) == null) models.remove(part) else models[part] = itemModel
        refreshItem(part)
    }

    override fun setGlow(rgb: Int) {
        glow = if (rgb < 0) -1 else rgb and 0xFFFFFF
        displays.values.filter { it.isValid }.forEach(::applyLook)
    }

    override fun setBrightness(level: Int) {
        brightness = if (level < 0) -1 else level.coerceAtMost(15)
        displays.values.filter { it.isValid }.forEach(::applyLook)
    }

    override fun teleport(location: Location) {
        attached = null
        current = location.clone().apply { pitch = 0f }
    }

    override fun attachTo(entity: Entity?) {
        attached = entity
        follow()
    }

    override fun attachedEntity(): Entity? = attached

    override fun location(): Location = current.clone()

    override fun isValid(): Boolean = !removed

    override fun remove() {
        removed = true
        attached = null
        despawn()
    }

    /** Tuning constants. */
    companion object {
        /** Ticks between pose and position updates. */
        const val UPDATE_TICKS = 2L

        /** Client interpolation length of a pose update. */
        const val INTERPOLATION_TICKS = 3

        /** How often a despawned rig checks whether its chunk is back. */
        const val RESPAWN_CHECK_TICKS = 20L

        /** Updates are only sent while a player is this close (blocks). */
        const val VIEW_RANGE = 48.0

        /** Squared distance below which the displays are not moved. */
        const val MOVE_EPSILON_SQ = 1e-6

        /** Yaw change (degrees) below which the displays are not turned. */
        const val YAW_EPSILON = 0.05f
    }
}
