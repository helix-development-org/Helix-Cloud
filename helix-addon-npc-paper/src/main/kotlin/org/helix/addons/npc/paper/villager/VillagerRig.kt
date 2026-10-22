package org.helix.addons.npc.paper.villager

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.helix.addons.npc.paper.rig.DisplayPose
import java.util.UUID
import kotlin.random.Random

/**
 * A custom animated villager made of display entities (design concept §12).
 *
 * One non-persistent [ItemDisplay] per rig part (item model `helix_villager:<id>/<part>`, transform `NONE`), an
 * [Interaction] for clicks and a [TextDisplay] name tag, all at the NPC location. [tick] must run every server tick:
 * speech advances per tick, the pose is re-solved every [UPDATE_TICKS] ticks and sent as interpolated display
 * transformations. Entities that vanish with their chunk are respawned once the chunk is loaded again.
 *
 * @property character the villager.
 * @property spec the villager's rig.
 * @param start NPC location (feet centre, yaw = facing).
 * @property hologramLines extra MiniMessage lines shown above the name.
 * @property follow whether the head follows the nearest player (NPC look mode `nearest`/`player`).
 */
class VillagerRig(
    val character: VillagerCharacter,
    private val spec: RigSpec,
    start: Location,
    private val hologramLines: List<String> = emptyList(),
    private val follow: Boolean = true,
) {
    private val mini = MiniMessage.miniMessage()

    /** NPC location (feet centre, yaw = facing); see [moveTo]. */
    var location: Location = start.clone()
        private set
    private val animator = VillagerAnimator(character.lidRest)
    private val speech = SpeechQueue(character.ticksPerLetter)
    private val parts = LinkedHashMap<String, ItemDisplay>()
    private val sent = HashMap<String, PartTransform>()
    private var interaction: Interaction? = null
    private var nameTag: TextDisplay? = null
    private var ticks = 0L
    private var delayToggle = false

    /** Seconds since the rig was created (animation clock). */
    private val now: Double get() = ticks / 20.0

    /** Whether the rig's entities currently exist. */
    val spawned: Boolean get() = interaction?.isValid == true && nameTag?.isValid == true && parts.values.all { it.isValid }

    /**
     * @param entity an entity.
     * @return whether [entity] is this rig's click hitbox.
     */
    fun owns(entity: Entity): Boolean = interaction?.uniqueId == entity.uniqueId

    /** The click hitbox's uuid, or `null` while despawned. */
    val interactionId: UUID? get() = interaction?.uniqueId

    /** Spawns the entities, if the chunk is loaded. Idempotent. */
    fun spawn() {
        if (spawned) return
        remove()
        val world = location.world ?: return
        if (!world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return
        val base = location.clone().apply { pitch = 0f }
        val pose = Kinematics.solve(spec, animator.frame(now, null, false))
        for (part in spec.parts.filter { it.model }) {
            parts[part.name] = world.spawn(base, ItemDisplay::class.java) { display ->
                display.isPersistent = false
                display.setItemStack(item(part.name))
                display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                display.interpolationDuration = INTERPOLATION_TICKS
                display.teleportDuration = 0
                display.shadowRadius = 0f
                pose[part.name]?.let {
                    display.transformation = DisplayPose.toBukkit(it)
                    sent[part.name] = it
                }
            }
        }
        val height = spec.height / Kinematics.PIXELS
        interaction = world.spawn(base, Interaction::class.java) { hitbox ->
            hitbox.isPersistent = false
            hitbox.interactionWidth = HITBOX_WIDTH
            hitbox.interactionHeight = height.toFloat()
            hitbox.isResponsive = true
        }
        nameTag = world.spawn(base.clone().add(0.0, height + NAME_GAP, 0.0), TextDisplay::class.java) { tag ->
            tag.isPersistent = false
            tag.billboard = Display.Billboard.CENTER
            tag.isShadowed = true
            tag.backgroundColor = Color.fromARGB(0, 0, 0, 0)
            tag.text(nameText())
        }
    }

    /**
     * Moves the villager: its entities are respawned at [target].
     *
     * @param target new feet position, yaw = facing.
     */
    fun moveTo(target: Location) {
        location = target.clone()
        remove()
        spawn()
    }

    /** Removes every entity of the rig. */
    fun remove() {
        parts.values.forEach { it.remove() }
        parts.clear()
        sent.clear()
        interaction?.remove()
        interaction = null
        nameTag?.remove()
        nameTag = null
    }

    /**
     * Advances the rig by one server tick: speech, then every [UPDATE_TICKS] the animation pose.
     */
    fun tick() {
        ticks++
        if (!spawned) {
            if (ticks % RESPAWN_CHECK_TICKS == 0L) spawn()
            return
        }
        speech.tick().forEach { event ->
            when (event) {
                is SpeechEvent.Line -> startLine(event.text)
                SpeechEvent.Babble -> play(VillagerVoice.babble(character.id), BABBLE_PITCH_MIN, BABBLE_PITCH_MAX)
            }
        }
        if (ticks % UPDATE_TICKS != 0L) return
        val viewers = location.getNearbyPlayers(VIEW_RANGE)
        if (viewers.isEmpty()) return
        val look = if (follow) lookTarget() else null
        val pose = Kinematics.solve(spec, animator.frame(now, look, speech.speaking))
        delayToggle = !delayToggle
        // The client only restarts interpolation when the delay value is re-sent: DisplayPose alternates it.
        DisplayPose.send(pose, parts, sent, delayToggle, INTERPOLATION_TICKS)
    }

    /**
     * Speaks a line: villager chat line to players within [VillagerVoice.RANGE] blocks, "Hmm" plus babble per
     * letter, mouth animation while it plays. Lines queue up; after two lines the villager pauses.
     *
     * @param text plain text (MiniMessage tags are shown literally).
     * @return `false` when the speech queue is full.
     */
    fun say(text: String): Boolean = speech.enqueue(text.trim())

    /** Raises the brow (address, offer, surprise). */
    fun raiseBrow() = animator.raiseBrow(now)

    /** Plays the cheer animation (victory, purchase, upgrade). */
    fun cheer() = animator.cheer(now)

    /** Plays the sulk animation (defeat, error). */
    fun sulk() = animator.sulk(now)

    /**
     * Puts the villager to sleep or wakes it.
     *
     * @param asleep `true` to sleep.
     */
    fun sleep(asleep: Boolean) {
        animator.sleeping = asleep
    }

    private fun startLine(text: String) {
        val component = mini.deserialize(VillagerCast.speakerPrefix(character))
            .append(Component.text(text).color(TextColor.fromHexString(VillagerCast.PARCHMENT)))
        location.getNearbyPlayers(VillagerVoice.RANGE).forEach { it.sendMessage(component) }
        play(VillagerVoice.hmm(character.id), 0.95f, 1.05f)
    }

    private fun play(key: String, minPitch: Float, maxPitch: Float) {
        val pitch = minPitch + Random.nextFloat() * (maxPitch - minPitch)
        val sound = Sound.sound(Key.key(key), Sound.Source.VOICE, 1f, pitch)
        val head = location.clone().add(0.0, spec.height / Kinematics.PIXELS * HEAD_HEIGHT, 0.0)
        location.getNearbyPlayers(VillagerVoice.RANGE).forEach { it.playSound(sound, head.x, head.y, head.z) }
    }

    private fun lookTarget(): Pair<Double, Double>? {
        val headPivot = spec.parts.firstOrNull { it.name == "head" }?.pivot?.get(1) ?: 20.0
        val eye = location.clone().add(0.0, headPivot / Kinematics.PIXELS + 0.3, 0.0)
        val player: Player = location.getNearbyPlayers(FOLLOW_RANGE)
            .filter { !it.isDead && it.gameMode != GameMode.SPECTATOR }
            .minByOrNull { it.location.distanceSquared(location) } ?: return null
        val target = player.eyeLocation
        return Kinematics.lookAngles(location.yaw.toDouble(), target.x - eye.x, target.y - eye.y, target.z - eye.z)
    }

    private fun nameText(): Component {
        val lines = hologramLines.map { mini.deserialize(it) } +
            mini.deserialize("<${character.robe}><b>${character.name}</b></${character.robe}>") +
            mini.deserialize("<${VillagerCast.WOOD_LIGHT}>${character.role}</${VillagerCast.WOOD_LIGHT}>")
        return Component.join(JoinConfiguration.newlines(), lines)
    }

    private fun item(part: String): ItemStack = ItemStack(Material.PAPER).apply {
        editMeta { it.itemModel = NamespacedKey(RigBundle.NAMESPACE, "${character.id}/$part") }
    }

    /** Tuning constants. */
    companion object {
        /** Ticks between pose updates (§12: update every 2 ticks). */
        const val UPDATE_TICKS = 2L

        /** Client interpolation length of a pose update. */
        const val INTERPOLATION_TICKS = 3

        /** How often a despawned rig checks whether its chunk is back. */
        const val RESPAWN_CHECK_TICKS = 20L

        /** Radius in which the head follows the nearest player (§12). */
        const val FOLLOW_RANGE = 6.0

        /** Poses are only sent while a player is this close. */
        const val VIEW_RANGE = 48.0

        /** Width of the click hitbox in blocks. */
        const val HITBOX_WIDTH = 0.9f

        /** Gap between the model top and the name tag, in blocks. */
        const val NAME_GAP = 0.15

        /** Sound source height as a fraction of the model height (about mouth level). */
        const val HEAD_HEIGHT = 0.75

        /** Babble pitch jitter range. */
        const val BABBLE_PITCH_MIN = 0.9f

        /** Babble pitch jitter range. */
        const val BABBLE_PITCH_MAX = 1.1f
    }
}
