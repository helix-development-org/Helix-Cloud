package org.helix.addons.cosmetics.paper

/**
 * `CustomModelData` values published by the cosmetics addon (its `CosmeticCatalog`) → resource-pack names
 * (pure, testable). Headwear names are item models (`helix_cosmetics:<name>`), wing names equipment assets
 * (`helix_cosmetics:<name>`, rendered on the elytra layer).
 */
object CosmeticModels {
    private val wings = mapOf(
        1001 to "wings_angel", 1002 to "wings_fairy", 1003 to "wings_ice",
        1004 to "wings_fire", 1005 to "wings_demon", 1006 to "wings_dragon",
    )
    private val headwear = mapOf(
        2001 to "crown_silver", 2002 to "crown_bronze", 2003 to "halo_white",
        2004 to "crown_gold", 2005 to "halo_dark", 2006 to "halo_rainbow",
    )

    /**
     * @param customModelData value from the `cosmetic.wings.<player>` bridge value.
     * @return the wings' equipment asset name, or null for an unknown value.
     */
    fun wings(customModelData: Int): String? = wings[customModelData]

    /**
     * @param customModelData value from the `cosmetic.headwear.<player>` bridge value.
     * @return the headwear's item model name, or null for an unknown value.
     */
    fun headwear(customModelData: Int): String? = headwear[customModelData]
}
