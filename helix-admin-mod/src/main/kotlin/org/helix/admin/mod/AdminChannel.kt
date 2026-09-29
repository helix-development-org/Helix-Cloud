package org.helix.admin.mod

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plugin-message channel `helix:admin` between this mod and the Paper component of the `helix.admin`
 * addon. Every message is one UTF-8 JSON object (raw bytes, no length prefix – what Bukkit's plugin
 * messaging delivers as-is) with a `type` field:
 *
 *  - client → server: `hello {version}`, `open` (request a login ticket), `call {id, op, args}` (in-game action)
 *  - server → client: `welcome {enabled}`, `ticket {url}`, `result {id, ok, data|error}`
 */
object AdminChannel {
    /** One JSON message on the channel. */
    data class Message(val json: JsonObject) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Message> = TYPE
    }

    // createType() only takes a path in the minecraft namespace, so the id is built explicitly
    val TYPE: CustomPacketPayload.Type<Message> =
        CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("helix", "admin"))

    private val CODEC: StreamCodec<FriendlyByteBuf, Message> = CustomPacketPayload.codec(
        { message, buf -> buf.writeBytes(message.json.toString().toByteArray(Charsets.UTF_8)) },
        { buf ->
            val bytes = ByteArray(buf.readableBytes()).also(buf::readBytes)
            Message(runCatching { JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject }.getOrDefault(JsonObject()))
        },
    )

    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val ids = AtomicInteger()

    /** Registers the payload type in both directions (called once at mod init). */
    fun registerTypes() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC)
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC)
    }

    /** Sends [json] to the server if it speaks the channel. */
    fun send(json: JsonObject): Boolean {
        if (!ClientPlayNetworking.canSend(TYPE)) return false
        ClientPlayNetworking.send(Message(json))
        return true
    }

    /** Sends a `call` and completes with the server's `result` message (or exceptionally on error). */
    fun call(op: String, args: JsonObject): CompletableFuture<JsonObject> {
        val id = ids.incrementAndGet()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        val sent = send(JsonObject().apply {
            addProperty("type", "call")
            addProperty("id", id)
            addProperty("op", op)
            add("args", args)
        })
        if (!sent) {
            pending.remove(id)
            future.completeExceptionally(IllegalStateException("server does not support Helix Admin"))
        }
        return future
    }

    /** Completes the `call` a `result` message answers. */
    fun onResult(json: JsonObject) {
        val id = json.get("id")?.asInt ?: return
        val future = pending.remove(id) ?: return
        if (json.get("ok")?.asBoolean == true) future.complete(json)
        else future.completeExceptionally(IllegalStateException(json.get("error")?.asString ?: "failed"))
    }

    /** Fails all outstanding calls (disconnect). */
    fun reset() {
        pending.values.forEach { it.completeExceptionally(IllegalStateException("disconnected")) }
        pending.clear()
    }
}
