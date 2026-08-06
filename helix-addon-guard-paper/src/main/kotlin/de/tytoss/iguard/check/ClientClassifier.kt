package de.tytoss.iguard.check

import java.nio.charset.StandardCharsets
import java.util.UUID

internal data class ClientFingerprint(
    val family: String,
    val confidence: String,
    val suspicious: Boolean,
    val signals: List<String>,
    val brandSpoofed: Boolean = false,
    val brandSpoofSignals: List<String> = emptyList(),
) {
    companion object {
        val UNKNOWN = ClientFingerprint("Unknown", "none", false, emptyList())
    }
}

internal object ClientClassifier {
    private val suspiciousFamilies = linkedMapOf(
        "liquidbounce" to "LiquidBounce",
        "wurst" to "Wurst",
        "meteor" to "Meteor",
        "aristois" to "Aristois",
        "impact" to "Impact",
        "sigma" to "Sigma",
    )
    private val declaredFamilies = linkedMapOf(
        "lunarclient" to "Lunar Client",
        "lunar" to "Lunar Client",
        "feather" to "Feather Client",
        "labymod" to "LabyMod",
        "badlion" to "Badlion Client",
        "quilt" to "Quilt",
        "forge" to "Forge",
        "fabric" to "Fabric / modded",
    )

    /**
     * Whether a uuid belongs to a Bedrock player connected through Geyser/Floodgate.
     *
     * Floodgate issues xuid-derived uuids whose most-significant 64 bits are zero
     * (`00000000-0000-0000-xxxx-xxxxxxxxxxxx`); the all-zero uuid is excluded. This is
     * dependency-free and reliable without requiring the Floodgate plugin API.
     *
     * @param uuid the player uuid.
     * @return `true` for a Bedrock/Floodgate player.
     */
    fun isBedrock(uuid: UUID): Boolean = uuid.mostSignificantBits == 0L && uuid.leastSignificantBits != 0L

    /** Classifies a client from its declared brand + registered plugin channels. */
    fun classify(brand: String?, channels: Set<String>): ClientFingerprint {
        val normalizedBrand = brand?.trim()?.lowercase().orEmpty()
        val normalizedChannels = channels.map(String::lowercase)
        val brandSpoofSignals = if (normalizedBrand == "vanilla") {
            normalizedChannels
                .filter(::isModLoaderChannel)
                .distinct()
                .take(8)
                .map { "channel:$it" }
        } else {
            emptyList()
        }
        val haystack = buildList {
            if (normalizedBrand.isNotEmpty()) add("brand:$normalizedBrand")
            normalizedChannels.forEach { add("channel:$it") }
        }
        suspiciousFamilies.entries.firstOrNull { (needle, _) -> haystack.any { needle in it } }?.let { (needle, family) ->
            return ClientFingerprint(
                family,
                "explicit",
                true,
                haystack.filter { needle in it }.take(4),
                brandSpoofSignals.isNotEmpty(),
                brandSpoofSignals,
            )
        }
        declaredFamilies.entries.firstOrNull { (needle, _) -> haystack.any { needle in it } }?.let { (needle, family) ->
            return ClientFingerprint(
                family,
                if (brandSpoofSignals.isEmpty()) "declared" else "inconsistent",
                false,
                haystack.filter { needle in it }.take(4),
                brandSpoofSignals.isNotEmpty(),
                brandSpoofSignals,
            )
        }
        if (normalizedBrand == "vanilla") {
            return ClientFingerprint(
                "Vanilla-compatible",
                if (brandSpoofSignals.isEmpty()) "declared" else "inconsistent",
                false,
                listOf("brand:vanilla"),
                brandSpoofSignals.isNotEmpty(),
                brandSpoofSignals,
            )
        }
        if (normalizedBrand.isNotEmpty()) {
            return ClientFingerprint(brand!!.take(64), "declared", false, listOf("brand:${brand.take(64)}"))
        }
        return ClientFingerprint.UNKNOWN
    }

    private fun isModLoaderChannel(channel: String): Boolean {
        val namespace = channel.substringBefore(':')
        return namespace == "fabric" || namespace.startsWith("fabric-") ||
            namespace == "quilt" || namespace.startsWith("quilt-") ||
            namespace == "forge" || namespace == "fml"
    }

    /** Decodes the minecraft:brand plugin-message payload (optionally VarInt-prefixed). */
    fun decodeBrand(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val (declaredLength, prefixBytes) = readVarInt(data)
        val bytes = if (declaredLength != null && declaredLength in 1..256 && prefixBytes + declaredLength <= data.size) {
            data.copyOfRange(prefixBytes, prefixBytes + declaredLength)
        } else {
            data
        }
        return String(bytes, StandardCharsets.UTF_8)
            .filter { it.code in 32..126 }
            .trim()
            .take(64)
            .ifEmpty { null }
    }

    /** Decodes a minecraft:register payload into the set of announced channel names. */
    fun decodeRegisteredChannels(data: ByteArray): Set<String> {
        return String(data, StandardCharsets.UTF_8)
            .split('\u0000')
            .asSequence()
            .map(String::trim)
            .filter { it.length in 1..128 && ':' in it }
            .take(128)
            .toSet()
    }

    private fun readVarInt(data: ByteArray): Pair<Int?, Int> {
        var result = 0
        var shift = 0
        for (index in 0 until minOf(data.size, 5)) {
            val value = data[index].toInt() and 0xff
            result = result or ((value and 0x7f) shl shift)
            if (value and 0x80 == 0) return result to index + 1
            shift += 7
        }
        return null to 0
    }
}
