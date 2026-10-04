package de.tytoss.igui.screen

/**
 * An animated picture on a full-window screen. Title glyphs cannot animate, items can: the sprite is an item in a
 * slot whose model (`item/generated` with an animated, `.mcmeta`-framed texture) is shifted and scaled in its `gui`
 * display transform so it lands at [centerX]/[centerY] in container pixels – also outside the slot grid, e.g. on the
 * wood of the board (pure; shared with the pack generators).
 *
 * @property model item model path below the namespace (`anim/torch_left` → `<ns>:anim/torch_left`).
 * @property texture texture path below `<ns>:item/` (`anim/torch`); one texture can serve many placements.
 * @property size frame size of the texture in pixels (drawn 1:1, so 32 → scale 2).
 * @property column slot column 0..8 the item sits in.
 * @property row slot row 0..9 (0..5 chest, 6..8 inventory, 9 hotbar).
 * @property centerX target centre x in container pixels.
 * @property centerY target centre y in container pixels.
 */
data class ScreenSprite(
    val model: String,
    val texture: String,
    val size: Int,
    val column: Int,
    val row: Int,
    val centerX: Int = ScreenSprites.slotCenterX(column),
    val centerY: Int = ScreenSprites.slotCenterY(row),
) {
    /** Shift to the right in pixels (model units in the GUI). */
    val translationX: Int get() = centerX - ScreenSprites.slotCenterX(column)

    /** Shift upwards in pixels (model y points up). */
    val translationY: Int get() = ScreenSprites.slotCenterY(row) - centerY

    /** Scale of the 16-unit item model so the texture is drawn 1:1. */
    val scale: Double get() = size / 16.0
}

/** Slot geometry and pack JSON of [ScreenSprite]s. */
object ScreenSprites {
    /** Minecraft clamps display translations to ±80 units. */
    const val MAX_SHIFT = 80

    /**
     * @param column slot column 0..8.
     * @return the centre x of an item in that column.
     */
    fun slotCenterX(column: Int): Int = 16 + column * 18

    /**
     * @param row slot row 0..9 of a 6-row chest with player inventory.
     * @return the centre y of an item in that row.
     */
    fun slotCenterY(row: Int): Int = when {
        row < 6 -> 26 + row * 18
        row < 9 -> 147 + (row - 6) * 18
        else -> 205
    }

    /**
     * @param namespace pack namespace.
     * @param sprite the placement.
     * @return the item model JSON (`models/item/<model>.json`).
     */
    fun modelJson(namespace: String, sprite: ScreenSprite): String {
        require(kotlin.math.abs(sprite.translationX) <= MAX_SHIFT && kotlin.math.abs(sprite.translationY) <= MAX_SHIFT) {
            "sprite ${sprite.model} is too far from its slot (${sprite.translationX}, ${sprite.translationY})"
        }
        val scale = sprite.scale.toString()
        return """{"parent":"minecraft:item/generated","textures":{"layer0":"$namespace:item/${sprite.texture}"},""" +
            """"display":{"gui":{"rotation":[0,0,0],"translation":[${sprite.translationX},${sprite.translationY},0],"scale":[$scale,$scale,$scale]}}}"""
    }

    /**
     * @param namespace pack namespace.
     * @param sprite the placement.
     * @return the item definition JSON (`items/<model>.json`).
     */
    fun itemJson(namespace: String, sprite: ScreenSprite): String =
        """{"model":{"type":"minecraft:model","model":"$namespace:item/${sprite.model}"}}"""
}
