package org.helix.addons.cosmetics

/**
 * One selectable cosmetic.
 *
 * @property id stable identifier, stored as the player's chosen value.
 * @property label display name shown in the profile GUI.
 * @property customModelData the `CustomModelData` value the Paper-side
 *  rendering component sets on the carrier item stack to select this
 *  cosmetic's model override in the bundled resource pack.
 * @property permission permission node required to choose this cosmetic;
 *  blank means available to everyone.
 */
data class CosmeticDefinition(
    val id: String,
    val label: String,
    val customModelData: Int,
    val permission: String = "",
)

/**
 * The fixed catalog of cosmetics this addon ships models and textures for
 * in its bundled resource pack (`helix-addon-cosmetics-paper`'s generated
 * pack.zip). Unlike subtitles, a cosmetic's visual is baked into the
 * resource pack at build time, so the catalog itself is not operator-
 * configurable at runtime — only which permission node gates each entry
 * could reasonably be, which is left as a future improvement.
 */
object CosmeticCatalog {
    /** Wings, worn on the back. */
    val wings: List<CosmeticDefinition> = listOf(
        CosmeticDefinition("angel", "Angel Wings", 1001),
        CosmeticDefinition("fairy", "Fairy Wings", 1002),
        CosmeticDefinition("ice", "Ice Wings", 1003),
        CosmeticDefinition("fire", "Fire Wings", 1004, "helix.cosmetics.wings.fire"),
        CosmeticDefinition("demon", "Demon Wings", 1005, "helix.cosmetics.wings.demon"),
        CosmeticDefinition("dragon", "Dragon Wings", 1006, "helix.cosmetics.wings.dragon"),
    )

    /** Headwear, worn above the head. */
    val headwear: List<CosmeticDefinition> = listOf(
        CosmeticDefinition("crown_silver", "Silver Crown", 2001),
        CosmeticDefinition("crown_bronze", "Bronze Crown", 2002),
        CosmeticDefinition("halo_white", "White Halo", 2003),
        CosmeticDefinition("crown_gold", "Gold Crown", 2004, "helix.cosmetics.headwear.crown_gold"),
        CosmeticDefinition("halo_dark", "Dark Halo", 2005, "helix.cosmetics.headwear.halo_dark"),
        CosmeticDefinition("halo_rainbow", "Rainbow Halo", 2006, "helix.cosmetics.headwear.halo_rainbow"),
    )
}
