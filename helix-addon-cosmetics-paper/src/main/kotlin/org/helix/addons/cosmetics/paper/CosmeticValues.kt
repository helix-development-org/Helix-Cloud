package org.helix.addons.cosmetics.paper

/**
 * Pure parsing of the cosmetic-specific slice of a bridge-values fetch.
 */
object CosmeticValues {
    private const val WINGS_PREFIX = "cosmetic.wings."
    private const val HEADWEAR_PREFIX = "cosmetic.headwear."

    /**
     * Extracts `cosmetic.wings.<player>` entries, keyed back to the plain
     * lowercase player name, with the value parsed as `CustomModelData`.
     *
     * @param bridgeValues the full bridge-values map.
     * @return lowercase player name to `CustomModelData`.
     */
    fun wings(bridgeValues: Map<String, String>): Map<String, Int> = parse(bridgeValues, WINGS_PREFIX)

    /**
     * Extracts `cosmetic.headwear.<player>` entries, keyed back to the
     * plain lowercase player name, with the value parsed as
     * `CustomModelData`.
     *
     * @param bridgeValues the full bridge-values map.
     * @return lowercase player name to `CustomModelData`.
     */
    fun headwear(bridgeValues: Map<String, String>): Map<String, Int> = parse(bridgeValues, HEADWEAR_PREFIX)

    private fun parse(bridgeValues: Map<String, String>, prefix: String): Map<String, Int> =
        bridgeValues.mapNotNull { (key, value) ->
            key.takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.let { player -> value.toIntOrNull()?.let { player to it } }
        }.toMap()
}
