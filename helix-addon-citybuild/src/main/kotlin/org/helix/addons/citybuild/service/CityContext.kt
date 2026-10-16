package org.helix.addons.citybuild.service

import org.helix.addons.citybuild.economy.MoneyResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.helix.addons.citybuild.config.ConfigStore
import org.helix.addons.citybuild.economy.KronenGateway
import org.helix.addons.citybuild.model.LogEntry
import org.helix.addons.citybuild.store.CityRepository
import org.helix.api.action.ActionResult
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * A rule violation reported to the caller as `ActionResult.error(code, message)`.
 *
 * @property code machine readable code, see `ACTIONS.md` (for example `limit`, `not_found`).
 * @param message human readable detail (English, for logs).
 */
class CityException(val code: String, message: String = code) : RuntimeException(message)

/**
 * Wrong or missing action arguments.
 *
 * @param message what is wrong.
 */
class UsageException(message: String) : RuntimeException(message)

/**
 * Positional action arguments with typed accessors. A JSON payload is always the last argument and
 * may contain spaces: [json] joins everything from its index on.
 *
 * @property values raw arguments.
 */
class Args(val values: List<String>) {
    /**
     * A required text argument.
     *
     * @param index position.
     * @param name argument name for the error message.
     * @return the value.
     */
    fun str(index: Int, name: String): String = values.getOrNull(index)?.takeIf { it.isNotBlank() } ?: throw UsageException("missing <$name>")

    /**
     * An optional text argument.
     *
     * @param index position.
     * @return the value or `null`.
     */
    fun opt(index: Int): String? = values.getOrNull(index)?.takeIf { it.isNotBlank() }

    /**
     * A required whole number.
     *
     * @param index position.
     * @param name argument name.
     * @return the value.
     */
    fun long(index: Int, name: String): Long = str(index, name).toLongOrNull() ?: throw UsageException("<$name> must be a number")

    /**
     * A required int.
     *
     * @param index position.
     * @param name argument name.
     * @return the value.
     */
    fun int(index: Int, name: String): Int = str(index, name).toIntOrNull() ?: throw UsageException("<$name> must be a number")

    /**
     * An optional int.
     *
     * @param index position.
     * @param default value when absent.
     * @return the value.
     */
    fun intOr(index: Int, default: Int): Int = opt(index)?.let { it.toIntOrNull() ?: throw UsageException("argument ${index + 1} must be a number") } ?: default

    /**
     * The JSON payload starting at [index] (the remaining arguments joined by spaces).
     *
     * @param index position of the payload.
     * @return the parsed JSON object.
     */
    fun json(index: Int): JsonObject {
        val text = values.drop(index).joinToString(" ").trim()
        if (text.isEmpty()) throw UsageException("missing <json>")
        return runCatching { CityContext.json.parseToJsonElement(text).jsonObject }.getOrElse { throw UsageException("payload must be a JSON object") }
    }

    /**
     * The raw JSON text starting at [index].
     *
     * @param index position of the payload.
     * @return the text.
     */
    fun text(index: Int): String = values.drop(index).joinToString(" ").trim().ifEmpty { throw UsageException("missing <json>") }
}

/**
 * Registers one action of a CityBuild service. Implemented by the addon; every CityBuild action is
 * bridge-invocable and answers with JSON in `lines[0]` or `error(code, message)`.
 */
fun interface ActionRegistrar {
    /**
     * Registers an action.
     *
     * @param name action name.
     * @param usage argument hint (`citybuild.home.set <uuid> <shard> <limit> <json>`).
     * @param description one-line summary.
     * @param permission permission node of admin actions (informational for bridge calls).
     * @param handler computes the result.
     */
    fun register(name: String, usage: String, description: String, permission: String?, handler: (Args) -> ActionResult)
}

/**
 * Shared dependencies of all CityBuild services.
 *
 * @property repository persistence.
 * @property config the config store.
 * @property kronen the network economy.
 * @property clock time source (injectable for tests).
 */
class CityContext(
    val repository: CityRepository,
    val config: ConfigStore,
    val kronen: KronenGateway,
    val clock: Clock = Clock.systemUTC(),
) {
    /** Current time in epoch milliseconds. */
    fun now(): Long = clock.millis()

    /** The configured calendar zone (`general.zone`). */
    fun zone(): ZoneId = runCatching { ZoneId.of(config.setting("general.zone") ?: "Europe/Berlin") }.getOrDefault(ZoneId.of("Europe/Berlin"))

    /** Today in the configured zone. */
    fun today(): LocalDate = LocalDate.now(clock.withZone(zone()))

    /**
     * An int setting.
     *
     * @param key setting key.
     * @param default fallback.
     * @return the value.
     */
    fun int(key: String, default: Int): Int = config.setting(key)?.toIntOrNull() ?: default

    /**
     * A long setting.
     *
     * @param key setting key.
     * @param default fallback.
     * @return the value.
     */
    fun long(key: String, default: Long): Long = config.setting(key)?.toLongOrNull() ?: default

    /**
     * An int list setting.
     *
     * @param key setting key.
     * @return the values (empty when unset).
     */
    fun ints(key: String): List<Int> = config.setting(key)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

    /**
     * A string list setting.
     *
     * @param key setting key.
     * @return the values (empty when unset).
     */
    fun strings(key: String): List<String> = config.setting(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /**
     * The body of a config document.
     *
     * @param collection collection name.
     * @param id document id.
     * @return the body or `null`.
     */
    fun doc(collection: String, id: String): JsonObject? = config.document(collection, id)?.body

    /**
     * Reverses a completed sale transfer after a later step failed: takes back what the seller
     * received (price minus the fee, rounded like the economy) and refunds the full price to the
     * buyer, so the buyer never loses money to an internal error. Never throws; outcomes are logged.
     *
     * @param buyer buyer uuid.
     * @param seller seller uuid.
     * @param price the transferred price.
     * @param feePermille the fee that was applied.
     * @param shard instance (for the log).
     * @param ref booking reference.
     */
    fun refundSale(buyer: String, seller: String, price: Long, feePermille: Int, shard: String, ref: String) {
        val received = price - price * feePermille / 1000
        val back = runCatching { kronen.withdraw(seller, received, "refund", ref) }.getOrElse { MoneyResult.fail("error") }
        val refund = runCatching { kronen.deposit(buyer, price, "refund", ref) }.getOrElse { MoneyResult.fail("error") }
        log(
            "refund", shard, buyer, seller, price, ref,
            jsonOf("sellerReversed" to back.ok, "buyerRefunded" to refund.ok, "received" to received),
        )
    }

    /**
     * Appends an audit log entry.
     *
     * @param kind log kind.
     * @param shard instance.
     * @param actor acting player.
     * @param target other party or object.
     * @param amount Kronen involved.
     * @param ref reference.
     * @param data JSON payload.
     */
    fun log(kind: String, shard: String, actor: String, target: String = "", amount: Long = 0, ref: String = "", data: JsonElement = JsonObject(emptyMap())) {
        runCatching { repository.log(LogEntry(0, kind, shard, actor, target, amount, ref, data.toString(), now())) }
    }

    /** Shared JSON codec. */
    companion object {
        /** Lenient JSON with defaults encoded. */
        val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    }
}

/**
 * Returns this object with the given fields set (overriding existing ones).
 *
 * @param fields field name to value (`String`, `Number`, `Boolean`, JSON element or `null`).
 * @return the merged object.
 */
fun JsonObject.with(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(this + fields.associate { (k, v) -> k to toJson(v) })

/**
 * Converts a plain value to JSON.
 *
 * @param value `String`, `Number`, `Boolean`, collection, map, JSON element or `null`.
 * @return the JSON element.
 */
fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    is Collection<*> -> JsonArray(value.map { toJson(it) })
    else -> JsonPrimitive(value.toString())
}

/**
 * Builds a JSON object from plain values.
 *
 * @param fields field name to value.
 * @return the object.
 */
fun jsonOf(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(fields.associate { (k, v) -> k to toJson(v) })

/** Common result helpers of the services. */
object Results {
    /**
     * A successful result carrying JSON.
     *
     * @param element the payload.
     * @return the result.
     */
    fun ok(element: JsonElement): ActionResult = ActionResult.ok(element.toString())

    /**
     * Throws a rule violation.
     *
     * @param code failure code.
     * @param message detail.
     * @return never.
     */
    fun fail(code: String, message: String = code): Nothing = throw CityException(code, message)
}
