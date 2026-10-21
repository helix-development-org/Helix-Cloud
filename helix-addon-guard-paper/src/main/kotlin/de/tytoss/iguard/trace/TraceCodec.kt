package de.tytoss.iguard.trace

import de.tytoss.iguard.api.ExemptionGrant
import de.tytoss.iguard.api.IGuardExemptionScope
import de.tytoss.iguard.model.AttackFrame
import de.tytoss.iguard.model.BlockAction
import de.tytoss.iguard.model.BlockActionFrame
import de.tytoss.iguard.model.Box
import de.tytoss.iguard.model.ClientAbilitiesFrame
import de.tytoss.iguard.model.ClientAction
import de.tytoss.iguard.model.ClientActionFrame
import de.tytoss.iguard.model.ClientIdentityFrame
import de.tytoss.iguard.model.ClientTickFrame
import de.tytoss.iguard.model.EnvironmentFrame
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import java.util.UUID

/**
 * The JSONL trace format (one JSON object per line, `type` tells the kind):
 *
 * - `header` — `version`, `label`, `player`, `name`, `server`, `startedAt`, `clientVersion`, `recipe`;
 * - `env` — a full sampled [EnvironmentFrame] (written whenever the snapshot used by the next frame
 *   changed): `at`, `tick`, `world`, `pos`, `yaw`, `pitch`, `box`, `boxes`, `supporting`,
 *   `colliding`, `gameMode`, `surface`, `slip`, `walkSpeed`, `moveSpeed`, `jump`, `gravity`, `step`,
 *   `eye`, `speedAmp`, `jumpAmp`, `ping`, `tps`, `vel`, `exemptEnv`, `tags`, `chunk`;
 * - `target` — an attack target's frames (`entityId`, `player`, `name`, `frames`: env objects);
 * - `frame` — one evaluated packet: `t`, `seq`, `kind`, `cv`, `tick`, `packet` (the packet fields),
 *   `env` (summary: `at`, `tags`, `support`, `physicalSupport`, `colliding`, `surface`, `slip`,
 *   `attributes`, `effects`, `ping`, `tps`, `gameMode`), `delta`, `eval` (predictions, uncertainty,
 *   airTicks … plus `limits`), `metrics`, `exempt`, `exemptions`, `results` (`check`, `failed`,
 *   `counted`, `weight`, `vl`, `alertVl`, `evidence`) and `vl` (all VLs after the frame);
 * - `exempt` — an external exemption grant (`t`, `millis`, `reason`, `scope`);
 * - `marker` — a free-text marker (`t`, `text`);
 * - `end` — last line: `t`, `reason`, `frames`, `dropped`.
 *
 * Replays only need `env`, `target`, `exempt` and the `packet` part of `frame` lines; everything
 * else is diagnostics.
 */
object TraceCodec {
    /** Format version written into the header. */
    const val VERSION: Int = 1

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The header line.
     *
     * @param playerId traced player.
     * @param playerName player name.
     * @param label recording label.
     * @param serverId server the recording runs on.
     * @param startedAt epoch millis of the start.
     * @param clientVersion the player's client version, or null when unknown.
     * @param recipe confidence recipe version.
     * @return one JSON line.
     */
    fun headerLine(
        playerId: UUID,
        playerName: String,
        label: String,
        serverId: String,
        startedAt: Long,
        clientVersion: String?,
        recipe: String,
    ): String = buildJsonObject {
        put("type", "header")
        put("version", VERSION)
        put("label", label)
        put("player", playerId.toString())
        put("name", playerName)
        put("server", serverId)
        put("startedAt", startedAt)
        clientVersion?.let { put("clientVersion", it) }
        put("recipe", recipe)
    }.toString()

    /** A full `env` line for [environment]. */
    fun environmentLine(environment: EnvironmentFrame): String = buildJsonObject {
        put("type", "env")
        environmentFields(environment)
    }.toString()

    /** A `target` line for an attack target. */
    fun targetLine(target: TraceTarget): String = buildJsonObject {
        put("type", "target")
        put("entityId", target.entityId)
        put("player", target.playerId.toString())
        put("name", target.playerName)
        putJsonArray("frames") { target.frames.forEach { add(buildJsonObject { environmentFields(it) }) } }
    }.toString()

    /** A `marker` line. */
    fun markerLine(at: Long, text: String): String = buildJsonObject {
        put("type", "marker")
        put("t", at)
        put("text", text)
    }.toString()

    /** An `exempt` line for an external exemption grant. */
    fun exemptionLine(grant: ExemptionGrant): String = buildJsonObject {
        put("type", "exempt")
        put("t", grant.grantedAt)
        put("millis", grant.millis)
        put("reason", grant.reason)
        put("scope", grant.scope.name)
    }.toString()

    /** The closing `end` line. */
    fun endLine(at: Long, reason: String, frames: Long, dropped: Long): String = buildJsonObject {
        put("type", "end")
        put("t", at)
        put("reason", reason)
        put("frames", frames)
        put("dropped", dropped)
    }.toString()

    /** A `frame` line with the packet, the environment summary, internals and verdicts. */
    fun frameLine(trace: FrameTrace): String = buildJsonObject {
        val frame = trace.frame
        put("type", "frame")
        put("t", frame.receivedAt)
        put("seq", frame.sequence)
        put("kind", FrameTrace.frameType(frame))
        put("cv", frame.clientVersion)
        put("tick", trace.clientTick)
        put("packet", packet(frame))
        val environment = trace.environment
        if (environment == null) {
            put("env", JsonNull)
        } else {
            putJsonObject("env") {
                put("at", environment.capturedAt)
                putJsonArray("tags") { environment.environmentTags.sorted().forEach { add(JsonPrimitive(it)) } }
                put("support", environment.supportingCollision)
                trace.physicalSupport?.let { put("physicalSupport", it) }
                put("colliding", environment.colliding)
                put("surface", environment.surface)
                put("slip", number(environment.surfaceSlipperiness))
                putJsonObject("attributes") {
                    put("moveSpeed", number(environment.movementSpeed))
                    put("walkSpeed", number(environment.walkSpeed.toDouble()))
                    put("jump", number(environment.jumpStrength))
                    put("gravity", number(environment.gravity))
                    put("step", number(environment.stepHeight))
                }
                putJsonObject("effects") {
                    put("speed", environment.speedAmplifier)
                    put("jump", environment.jumpAmplifier)
                }
                put("ping", environment.ping)
                put("tps", number(environment.tps))
                put("gameMode", environment.gameMode)
            }
        }
        trace.delta?.let { put("delta", vector(it)) }
        putJsonObject("eval") {
            trace.internals.forEach { (key, value) -> put(key, any(value)) }
            if (trace.limits.isNotEmpty()) putJsonObject("limits") { trace.limits.forEach { (key, value) -> put(key, number(value)) } }
        }
        if (trace.metrics.isNotEmpty()) putJsonObject("metrics") { trace.metrics.forEach { (key, value) -> put(key, any(value)) } }
        put("exempt", trace.exempt)
        if (trace.exemptions.isNotEmpty()) putJsonArray("exemptions") { trace.exemptions.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("results") {
            trace.results.forEach { result ->
                add(
                    buildJsonObject {
                        put("check", result.checkId)
                        put("failed", result.failed)
                        if (result.failed) {
                            put("counted", result.counted)
                            put("weight", number(result.weight))
                        }
                        put("vl", number(result.violationLevel))
                        put("alertVl", number(result.alertLevel))
                        if (result.evidence.isNotEmpty()) putJsonObject("evidence") { result.evidence.forEach { (key, value) -> put(key, any(value)) } }
                    },
                )
            }
        }
        putJsonObject("vl") { trace.violationLevels.toSortedMap().forEach { (key, value) -> put(key, number(value)) } }
    }.toString()

    /**
     * Parses one trace line.
     *
     * @param line one JSONL line.
     * @return the JSON object, or null for blank or malformed lines.
     */
    fun parseLine(line: String): JsonObject? =
        line.takeIf(String::isNotBlank)?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    /** Decodes an `env` object (or a target frame) back into an [EnvironmentFrame]. */
    fun decodeEnvironment(source: JsonObject): EnvironmentFrame = EnvironmentFrame(
        capturedAt = source.long("at"),
        tick = source.long("tick"),
        worldId = UUID.fromString(source.string("world")),
        position = source.vector("pos"),
        yaw = source.double("yaw").toFloat(),
        pitch = source.double("pitch").toFloat(),
        entityBox = source.box("box"),
        collisionBoxes = source["boxes"]?.jsonArray?.map { box(it.jsonArray) } ?: emptyList(),
        supportingCollision = source.boolean("supporting"),
        colliding = source.boolean("colliding"),
        gameMode = source.string("gameMode"),
        surface = source.string("surface"),
        surfaceSlipperiness = source.double("slip"),
        walkSpeed = source.double("walkSpeed").toFloat(),
        movementSpeed = source.double("moveSpeed"),
        jumpStrength = source.double("jump"),
        gravity = source.double("gravity"),
        stepHeight = source.double("step"),
        eyeHeight = source.double("eye"),
        speedAmplifier = source.long("speedAmp").toInt(),
        jumpAmplifier = source.long("jumpAmp").toInt(),
        ping = source.long("ping").toInt(),
        tps = source.double("tps"),
        velocity = source.vector("vel"),
        exemptEnvironment = source.boolean("exemptEnv"),
        environmentTags = source["tags"]?.jsonArray?.mapTo(LinkedHashSet()) { it.jsonPrimitive.content } ?: emptySet(),
        chunkLoaded = source["chunk"]?.jsonPrimitive?.booleanOrNull ?: true,
    )

    /**
     * Decodes the packet of a `frame` line.
     *
     * @param line the frame line object.
     * @param playerId the traced player (from the header).
     * @return the packet frame, or null for unknown kinds.
     */
    fun decodeFrame(line: JsonObject, playerId: UUID): PacketFrame? {
        val at = line.long("t")
        val sequence = line.long("seq")
        val version = line.string("cv")
        val packet = line["packet"]?.jsonObject ?: JsonObject(emptyMap())
        return when (line.string("kind")) {
            "movement" -> MovementFrame(
                playerId, sequence, at, version,
                packet.boolean("posChanged"), packet.boolean("rotChanged"), packet.vector("pos"),
                packet.double("yaw").toFloat(), packet.double("pitch").toFloat(), packet.boolean("onGround"),
            )
            "attack" -> AttackFrame(playerId, sequence, at, version, packet.long("target").toInt())
            "swing" -> SwingFrame(playerId, sequence, at, version)
            "action" -> ClientActionFrame(playerId, sequence, at, version, enumOr(packet.string("action"), ClientAction.OTHER))
            "abilities" -> ClientAbilitiesFrame(playerId, sequence, at, version, packet.boolean("flying"))
            "tick" -> ClientTickFrame(playerId, sequence, at, version)
            "identity" -> ClientIdentityFrame(
                playerId, sequence, at, version, packet.string("channel"),
                runCatching { Base64.getDecoder().decode(packet.string("payload")) }.getOrDefault(ByteArray(0)),
            )
            "timeline" -> TimelineFrame(playerId, sequence, at, version, enumOr(packet.string("kind"), TimelineKind.SERVER_PING), packet.long("id"))
            "block" -> BlockActionFrame(
                playerId, sequence, at, version, enumOr(packet.string("action"), BlockAction.USE_ITEM),
                packet.long("x").toInt(), packet.long("y").toInt(), packet.long("z").toInt(), packet.string("face"),
                packet.long("sequence").toInt(),
                packet.vector("cursor").x.toFloat(), packet.vector("cursor").y.toFloat(), packet.vector("cursor").z.toFloat(),
            )
            "inventory" -> InventoryClickFrame(
                playerId, sequence, at, version, packet.long("window").toInt(),
                packet["state"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(), packet.long("slot").toInt(),
                packet.long("button").toInt(), packet.string("clickType"),
            )
            "velocity" -> ServerVelocityFrame(playerId, sequence, at, version, packet.vector("velocity"), packet.string("source"))
            "teleport" -> {
                val relative = packet["relative"]?.jsonArray?.map { it.jsonPrimitive.booleanOrNull == true } ?: List(5) { false }
                ServerTeleportFrame(
                    playerId, sequence, at, version, packet.long("id").toInt(), packet.vector("pos"), packet.vector("delta"),
                    packet.double("yaw").toFloat(), packet.double("pitch").toFloat(),
                    relative.getOrElse(0) { false }, relative.getOrElse(1) { false }, relative.getOrElse(2) { false },
                    relative.getOrElse(3) { false }, relative.getOrElse(4) { false },
                )
            }
            "teleport-confirm" -> TeleportConfirmFrame(playerId, sequence, at, version, packet.long("id").toInt())
            "server-abilities" -> ServerAbilitiesFrame(
                playerId, sequence, at, version, packet.boolean("flying"), packet.boolean("allowed"),
                packet.boolean("creative"), packet.double("scale").toFloat(),
            )
            "reset" -> ResetFrame(playerId, sequence, at, version, packet.string("reason"))
            else -> null
        }
    }

    /** Decodes an `exempt` line for [playerId]. */
    fun decodeExemption(line: JsonObject, playerId: UUID): ExemptionGrant = ExemptionGrant(
        playerId,
        line.long("t"),
        line.long("millis").coerceAtLeast(1),
        line.string("reason").ifBlank { "trace" },
        enumOr(line.string("scope"), IGuardExemptionScope.ALL),
    )

    private fun packet(frame: PacketFrame): JsonObject = buildJsonObject {
        when (frame) {
            is MovementFrame -> {
                put("pos", vector(frame.position))
                put("yaw", number(frame.yaw.toDouble()))
                put("pitch", number(frame.pitch.toDouble()))
                put("onGround", frame.onGround)
                put("posChanged", frame.positionChanged)
                put("rotChanged", frame.rotationChanged)
            }
            is AttackFrame -> put("target", frame.targetEntityId)
            is SwingFrame, is ClientTickFrame -> Unit
            is ClientActionFrame -> put("action", frame.action.name)
            is ClientAbilitiesFrame -> put("flying", frame.flying)
            is ClientIdentityFrame -> {
                put("channel", frame.channel)
                put("payload", Base64.getEncoder().encodeToString(frame.payload))
            }
            is TimelineFrame -> {
                put("kind", frame.kind.name)
                put("id", frame.id)
            }
            is BlockActionFrame -> {
                put("action", frame.action.name)
                put("x", frame.blockX)
                put("y", frame.blockY)
                put("z", frame.blockZ)
                put("face", frame.face)
                put("sequence", frame.interactionSequence)
                put("cursor", vector(Vec3(frame.cursorX.toDouble(), frame.cursorY.toDouble(), frame.cursorZ.toDouble())))
            }
            is InventoryClickFrame -> {
                put("window", frame.windowId)
                frame.stateId?.let { put("state", it) }
                put("slot", frame.slot)
                put("button", frame.button)
                put("clickType", frame.clickType)
            }
            is ServerVelocityFrame -> {
                put("velocity", vector(frame.velocity))
                put("source", frame.source)
            }
            is ServerTeleportFrame -> {
                put("id", frame.teleportId)
                put("pos", vector(frame.position))
                put("delta", vector(frame.deltaMovement))
                put("yaw", number(frame.yaw.toDouble()))
                put("pitch", number(frame.pitch.toDouble()))
                putJsonArray("relative") {
                    listOf(frame.relativeX, frame.relativeY, frame.relativeZ, frame.relativeYaw, frame.relativePitch).forEach { add(JsonPrimitive(it)) }
                }
            }
            is TeleportConfirmFrame -> put("id", frame.teleportId)
            is ServerAbilitiesFrame -> {
                put("flying", frame.flying)
                put("allowed", frame.flightAllowed)
                put("creative", frame.creative)
                put("scale", number(frame.movementScale.toDouble()))
            }
            is ResetFrame -> put("reason", frame.reason)
        }
    }

    private fun JsonObjectBuilder.environmentFields(environment: EnvironmentFrame) {
        put("at", environment.capturedAt)
        put("tick", environment.tick)
        put("world", environment.worldId.toString())
        put("pos", vector(environment.position))
        put("yaw", number(environment.yaw.toDouble()))
        put("pitch", number(environment.pitch.toDouble()))
        put("box", box(environment.entityBox))
        putJsonArray("boxes") { environment.collisionBoxes.forEach { add(box(it)) } }
        put("supporting", environment.supportingCollision)
        put("colliding", environment.colliding)
        put("gameMode", environment.gameMode)
        put("surface", environment.surface)
        put("slip", number(environment.surfaceSlipperiness))
        put("walkSpeed", number(environment.walkSpeed.toDouble()))
        put("moveSpeed", number(environment.movementSpeed))
        put("jump", number(environment.jumpStrength))
        put("gravity", number(environment.gravity))
        put("step", number(environment.stepHeight))
        put("eye", number(environment.eyeHeight))
        put("speedAmp", environment.speedAmplifier)
        put("jumpAmp", environment.jumpAmplifier)
        put("ping", environment.ping)
        put("tps", number(environment.tps))
        put("vel", vector(environment.velocity))
        put("exemptEnv", environment.exemptEnvironment)
        putJsonArray("tags") { environment.environmentTags.sorted().forEach { add(JsonPrimitive(it)) } }
        put("chunk", environment.chunkLoaded)
    }

    // Non-finite doubles (bad packets) are not valid JSON numbers; they travel as strings.
    private fun number(value: Double): JsonPrimitive = if (value.isFinite()) JsonPrimitive(value) else JsonPrimitive(value.toString())

    private fun vector(value: Vec3): JsonArray = buildJsonArray {
        add(number(value.x))
        add(number(value.y))
        add(number(value.z))
    }

    private fun box(value: Box): JsonArray = buildJsonArray {
        listOf(value.minX, value.minY, value.minZ, value.maxX, value.maxY, value.maxZ).forEach { add(number(it)) }
    }

    private fun any(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> number(value.toDouble())
        is Double -> number(value)
        is Number -> number(value.toDouble())
        else -> JsonPrimitive(value.toString())
    }

    private fun box(values: JsonArray): Box {
        val numbers = values.map { it.jsonPrimitive.content.toDouble() }
        return Box(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5])
    }

    private fun JsonObject.box(key: String): Box = get(key)?.jsonArray?.let(::box) ?: Box(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    private fun JsonObject.vector(key: String): Vec3 {
        val values = get(key)?.jsonArray?.map { it.jsonPrimitive.content.toDouble() } ?: return Vec3(0.0, 0.0, 0.0)
        return Vec3(values.getOrElse(0) { 0.0 }, values.getOrElse(1) { 0.0 }, values.getOrElse(2) { 0.0 })
    }

    private fun JsonObject.string(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.long(key: String): Long = get(key)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L

    private fun JsonObject.double(key: String): Double = get(key)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0

    private fun JsonObject.boolean(key: String): Boolean = get(key)?.jsonPrimitive?.booleanOrNull ?: false

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(fallback)
}
