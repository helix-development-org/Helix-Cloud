package de.tytoss.igui.pagination

import de.tytoss.igui.display.DisplayBuilder
import de.tytoss.igui.texture.GuiTexture
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor

/** Which page-navigation arrows are usable, used to pick the matching texture from [PaginationTextures]. */
enum class PaginationState {
    /** Both a previous and a next page exist. */
    BOTH,

    /** Only a previous page exists (currently on the last page). */
    PREVIOUS_ONLY,

    /** Only a next page exists (currently on the first page). */
    NEXT_ONLY,

    /** Only one page exists; both arrows are disabled. */
    DISABLED,
}

/**
 * The four arrow-button textures needed to render pagination controls, one
 * per [PaginationState], typically drawn via [pagination].
 *
 * @property both texture shown when both previous and next are available.
 * @property previousOnly texture shown when only previous is available.
 * @property nextOnly texture shown when only next is available.
 * @property disabled texture shown when there is only one page.
 */
data class PaginationTextures(
    val both: GuiTexture,
    val previousOnly: GuiTexture,
    val nextOnly: GuiTexture,
    val disabled: GuiTexture,
) {
    /**
     * Picks the texture matching a navigation state.
     *
     * @param state the current navigation state.
     * @return the matching texture.
     */
    operator fun get(state: PaginationState): GuiTexture = when (state) {
        PaginationState.BOTH -> both
        PaginationState.PREVIOUS_ONLY -> previousOnly
        PaginationState.NEXT_ONLY -> nextOnly
        PaginationState.DISABLED -> disabled
    }
}

/**
 * Draws a centered pagination arrow (picked from [textures] based on
 * [page]/[pageCount]) and an optional "current/total" label beneath it, at
 * the current cursor position.
 *
 * @param textures the arrow textures to pick from.
 * @param page the current page number, 1-based.
 * @param pageCount the total number of pages; must be at least 1.
 * @param labelLine text row to draw the label on, or `null` to skip it.
 * @param label formats the label text from the current page and total page count,
 *  or `null` to skip it.
 * @param labelColor color of the label text.
 */
fun DisplayBuilder.pagination(
    textures: PaginationTextures,
    page: Int,
    pageCount: Int,
    labelLine: Int? = 3,
    label: ((Int, Int) -> String)? = { current, total -> "$current/$total" },
    labelColor: TextColor = NamedTextColor.DARK_GRAY,
) {
    require(pageCount >= 1) { "Page count must be at least one" }
    require(page in 1..pageCount) { "Page must be in 1..$pageCount" }
    val state = when {
        pageCount == 1 -> PaginationState.DISABLED
        page == 1 -> PaginationState.NEXT_ONLY
        page == pageCount -> PaginationState.PREVIOUS_ONLY
        else -> PaginationState.BOTH
    }
    centeredTexture(textures[state])
    if (labelLine != null && label != null) {
        centeredText(label(page, pageCount), labelLine, color = labelColor)
    }
}

/**
 * Slices out one page's worth of elements.
 *
 * @param page the 1-based page number to slice.
 * @param pageSize number of elements per page.
 * @return the elements on that page, or an empty list if [page] is past the end.
 */
fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
    require(page >= 1) { "Page must be at least one" }
    require(pageSize >= 1) { "Page size must be at least one" }
    val from = (page - 1L) * pageSize
    if (from >= size) return emptyList()
    return subList(from.toInt(), minOf(size, from.toInt() + pageSize))
}

/**
 * Computes how many pages a list of items splits into.
 *
 * @param itemCount total number of items; must not be negative.
 * @param pageSize number of items per page; must be at least 1.
 * @return the number of pages, always at least 1 (even for zero items).
 */
fun pageCount(itemCount: Int, pageSize: Int): Int {
    require(itemCount >= 0) { "Item count must not be negative" }
    require(pageSize >= 1) { "Page size must be at least one" }
    return maxOf(1, ((itemCount.toLong() + pageSize - 1L) / pageSize).toInt())
}
