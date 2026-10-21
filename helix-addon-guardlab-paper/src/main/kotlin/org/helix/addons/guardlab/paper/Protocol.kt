package org.helix.addons.guardlab.paper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Wire format of the `helix:guardlab` plugin channel: one UTF-8 JSON object per message.
 *
 * Server → client: `hello`, `scenario`, `abort`, `done`, `quit`. Client → server: `ready`, `started`,
 * `finished`, `error`. The contract is shared with the GuardLab dev client (helix-labymod-addon).
 */
object Protocol {
    /** Plugin channel. */
    const val CHANNEL = "helix:guardlab"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `hello` – sent once the client registered the channel.
     *
     * @param token shared lab token the client must match.
     * @param suite suite that will run.
     * @param scenarios number of scenarios of that suite.
     * @return the encoded message.
     */
    fun hello(token: String, suite: String, scenarios: Int): ByteArray = encode(
        buildJsonObject {
            put("type", "hello")
            put("token", token)
            put("suite", suite)
            put("scenarios", scenarios)
        },
    )

    /**
     * `scenario` – the client plays [script] with [cheat] for [durationTicks].
     *
     * @param id scenario id.
     * @param index 0-based position in the suite.
     * @param setupTicks ticks the server spent preparing (informational).
     * @param script the step list (passed through from the suite file).
     * @param cheat `{mode, params}`.
     * @param durationTicks playback length the client should not exceed.
     * @return the encoded message.
     */
    fun scenario(id: String, index: Int, setupTicks: Int, script: JsonArray, cheat: JsonObject, durationTicks: Int): ByteArray = encode(
        buildJsonObject {
            put("type", "scenario")
            put("id", id)
            put("index", index)
            put("setupTicks", setupTicks)
            put("script", script)
            put("cheat", cheat)
            put("durationTicks", durationTicks)
        },
    )

    /**
     * `abort` – the run was stopped.
     *
     * @param reason human readable reason.
     * @return the encoded message.
     */
    fun abort(reason: String): ByteArray = encode(buildJsonObject { put("type", "abort"); put("reason", reason) })

    /**
     * `done` – the run finished.
     *
     * @param report report path relative to the server workspace.
     * @param passed passed scenarios (including warnings).
     * @param failed failed scenarios.
     * @return the encoded message.
     */
    fun done(report: String, passed: Int, failed: Int): ByteArray = encode(
        buildJsonObject {
            put("type", "done")
            put("report", report)
            put("passed", passed)
            put("failed", failed)
        },
    )

    /** @return the encoded `quit` message (the client may disconnect). */
    fun quit(): ByteArray = encode(buildJsonObject { put("type", "quit") })

    /**
     * Parses a client message.
     *
     * @param bytes raw payload.
     * @return the message, or null when it is not a valid client message.
     */
    fun parseClient(bytes: ByteArray): ClientMessage? {
        val obj = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.content ?: return null
        val str = { key: String -> (obj[key] as? JsonPrimitive)?.content.orEmpty() }
        return when (type) {
            "ready" -> ClientMessage.Ready(str("version"), str("brand"))
            "started" -> ClientMessage.Started(str("id"))
            "finished" -> ClientMessage.Finished(str("id"), (obj["ticks"] as? JsonPrimitive)?.intOrNull ?: 0)
            "error" -> ClientMessage.Error(str("id"), str("reason"))
            else -> null
        }
    }

    private fun encode(obj: JsonObject): ByteArray = obj.toString().toByteArray(Charsets.UTF_8)
}

/** A message sent by the GuardLab client. */
sealed interface ClientMessage {
    /**
     * The client verified the token and is ready.
     *
     * @property version client (mod) version.
     * @property brand client brand.
     */
    data class Ready(val version: String, val brand: String) : ClientMessage

    /**
     * Playback of a scenario started.
     *
     * @property id scenario id.
     */
    data class Started(val id: String) : ClientMessage

    /**
     * Playback of a scenario finished.
     *
     * @property id scenario id.
     * @property ticks ticks actually played.
     */
    data class Finished(val id: String, val ticks: Int) : ClientMessage

    /**
     * The client could not play a scenario (e.g. unsupported cheat mode).
     *
     * @property id scenario id (may be empty).
     * @property reason reason, `unsupported…` marks an unsupported mode.
     */
    data class Error(val id: String, val reason: String) : ClientMessage
}

/** Small JSON helpers shared by the suite model. */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
