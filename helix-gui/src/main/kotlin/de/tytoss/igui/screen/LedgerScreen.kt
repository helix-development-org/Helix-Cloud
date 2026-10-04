package de.tytoss.igui.screen

import de.tytoss.igui.display.DisplayBuilder
import de.tytoss.igui.gui.GuiClickContext
import de.tytoss.igui.gui.GuiPageBuilder
import de.tytoss.igui.layout.GuiArea
import de.tytoss.igui.layout.GuiGrid
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One line of a side note.
 *
 * @property text the text.
 * @property color its colour.
 */
data class LedgerLine(val text: String, val color: TextColor = LedgerScreen.INK)

/**
 * One list row.
 *
 * @property label text on the left.
 * @property value text on the right (e.g. a setting's value).
 * @property labelColor colour of the label.
 * @property valueColor colour of the value.
 * @property icon an item shown in the row's first cell (e.g. a player head), or null.
 * @property tooltip tooltip lines when hovering the row (first line = title).
 * @property badge an animated badge in the row's last cell (the value moves left of it), or null.
 */
data class LedgerRow(
    val label: String,
    val value: String = "",
    val labelColor: TextColor = LedgerScreen.INK,
    val valueColor: TextColor = LedgerScreen.INK,
    val icon: ItemStack? = null,
    val tooltip: List<Component> = emptyList(),
    val badge: LedgerBadge? = null,
)

/**
 * Animated badges for a row's last cell.
 *
 * @property sprite the sprite drawn.
 */
enum class LedgerBadge(val sprite: ScreenSprite) {
    /** Pulsing red wax seal (unread). */
    SEAL(LedgerLayout.SEAL),

    /** Glowing green gem (on). */
    GEM_ON(LedgerLayout.GEM_ON),

    /** Dull red gem (off). */
    GEM_OFF(LedgerLayout.GEM_OFF),

    /** Pulsing warning rune (danger). */
    RUNE(LedgerLayout.RUNE),
}

/**
 * What a ledger shows to one player.
 *
 * @property title text on the ribbon.
 * @property subtitle text under the ribbon.
 * @property rows rows of the current page (max. 8).
 * @property page current page (0-based).
 * @property pages page count.
 * @property left lines of the left note.
 * @property right lines of the right note.
 * @property buttons labels of the two footer buttons (null: none).
 * @property quill whether a quill writes on the right note (e.g. letters).
 */
data class LedgerView(
    val title: String,
    val subtitle: String = "",
    val rows: List<LedgerRow> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    val left: List<LedgerLine> = emptyList(),
    val right: List<LedgerLine> = emptyList(),
    val buttons: List<String?> = listOf(null, null),
    val quill: Boolean = false,
)

/**
 * The shared **ledger** screen: a medieval full-window list (see [LedgerLayout]) for menus like the profile,
 * messages or staff tools. Consumers keep their own state, put the current [LedgerView] per player with [show] and
 * re-render the GUI; clicks come back as row index, button index or page step.
 *
 * ```
 * val ledger = LedgerScreen()
 * igui.gui("profile") {
 *     rows = 6; playerInventory = true
 *     page("main") { ledger.install(this, onRow = { ctx, i -> … }, onButton = { ctx, b -> … }, onPage = { ctx, d -> … }) }
 * }
 * ```
 */
class LedgerScreen {
    private val views = ConcurrentHashMap<UUID, LedgerView>()

    /**
     * Sets what [player] sees (call before opening or refreshing).
     *
     * @param player the viewer.
     * @param view the content.
     */
    fun show(player: Player, view: LedgerView) {
        views[player.uniqueId] = view
    }

    /**
     * @param player a viewer.
     * @return what the viewer currently sees, or null.
     */
    fun viewOf(player: Player): LedgerView? = views[player.uniqueId]

    /**
     * Forgets a viewer (close/quit).
     *
     * @param player the viewer.
     */
    fun forget(player: Player) {
        views.remove(player.uniqueId)
    }

    /**
     * Draws the ledger on [page] (a page of a `playerInventory` GUI with 6 rows) and wires the clicks.
     *
     * @param page the page builder.
     * @param onRow a row (0..7 on the current page) was clicked.
     * @param onButton footer button 0 or 1 was clicked.
     * @param onPage an arrow was clicked (-1 / +1).
     */
    fun install(
        page: GuiPageBuilder,
        onRow: suspend (GuiClickContext, Int) -> Unit,
        onButton: suspend (GuiClickContext, Int) -> Unit = { _, _ -> },
        onPage: suspend (GuiClickContext, Int) -> Unit = { _, _ -> },
    ) {
        val grid = GuiGrid(6, true)
        page.cancelAllInteractions = true
        page.title { ctx -> views[ctx.player.uniqueId]?.let { draw(it) } }
        // animated decoration in the header row: torches on the posts, candles by the ribbon, the quill
        listOf(LedgerLayout.TORCH_LEFT, LedgerLayout.TORCH_RIGHT, LedgerLayout.CANDLE_LEFT, LedgerLayout.CANDLE_RIGHT).forEach { sprite ->
            val item = SpriteItems.of(LedgerLayout.SPRITE_NAMESPACE, sprite)
            page.item(grid.slot(sprite.column, sprite.row)) { item.clone() }
        }
        val quill = SpriteItems.of(LedgerLayout.SPRITE_NAMESPACE, LedgerLayout.QUILL)
        page.item(grid.slot(LedgerLayout.QUILL.column, LedgerLayout.QUILL.row)) { ctx ->
            if (views[ctx.player.uniqueId]?.quill == true) quill.clone() else null
        }
        for (index in 0 until LedgerLayout.ROWS) {
            val gridRow = LedgerLayout.FIRST_ROW + index
            for (column in 0 until GuiGrid.COLUMNS) {
                page.item(grid.slot(column, gridRow)) { ctx ->
                    val row = views[ctx.player.uniqueId]?.rows?.getOrNull(index) ?: return@item null
                    when {
                        column == 0 && row.icon != null -> withTooltip(row.icon.clone(), row.tooltip)
                        column == GuiGrid.COLUMNS - 1 && row.badge != null ->
                            SpriteItems.of(LedgerLayout.SPRITE_NAMESPACE, row.badge.sprite, row.tooltip)
                        else -> tooltipItem(row.tooltip)
                    }
                }
            }
            page.onClick(GuiArea(0, gridRow, GuiGrid.COLUMNS, 1)) { ctx ->
                if (views[ctx.player.uniqueId]?.rows?.getOrNull(index) != null) onRow(ctx, index)
            }
        }
        LedgerLayout.BUTTON_COLUMNS.forEachIndexed { b, columns ->
            page.onClick(GuiArea(columns.first, LedgerLayout.FOOTER_ROW, columns.count(), 1)) { ctx ->
                if (views[ctx.player.uniqueId]?.buttons?.getOrNull(b) != null) onButton(ctx, b)
            }
        }
        page.onClick(grid.slot(0, LedgerLayout.FOOTER_ROW)) { ctx ->
            val view = views[ctx.player.uniqueId] ?: return@onClick
            if (view.page > 0) onPage(ctx, -1)
        }
        page.onClick(grid.slot(8, LedgerLayout.FOOTER_ROW)) { ctx ->
            val view = views[ctx.player.uniqueId] ?: return@onClick
            if (view.page < view.pages - 1) onPage(ctx, 1)
        }
    }

    private fun DisplayBuilder.draw(view: LedgerView) {
        val font = Key.key(LedgerLayout.FONT)
        ScreenFonts.tiles(LedgerLayout.BG_W, LedgerLayout.BG_H).forEachIndexed { i, tile ->
            glyphAt(LedgerLayout.BG_X + tile.x, font, LedgerLayout.BG_TILE + i, tile.width + 1)
        }
        if (view.page > 0) glyphAt(LedgerLayout.ARROW_LEFT_X, font, LedgerLayout.ARROW_LEFT, LedgerLayout.ARROW_SIZE + 1)
        if (view.page < view.pages - 1) glyphAt(LedgerLayout.ARROW_RIGHT_X, font, LedgerLayout.ARROW_RIGHT, LedgerLayout.ARROW_SIZE + 1)
        view.buttons.take(2).forEachIndexed { b, label ->
            if (label == null) return@forEachIndexed
            val x = LedgerLayout.BUTTON_X[b]
            glyphAt(x, font, LedgerLayout.BUTTON, LedgerLayout.BUTTON_W + 1)
            textAt(fit(label, LedgerLayout.BUTTON_W - 6), x + LedgerLayout.BUTTON_W / 2, LedgerLayout.FOOTER_TOP + 4, LIGHT, TextAlign.CENTER)
        }
        if (view.pages > 1) {
            textAt("${view.page + 1}/${view.pages}", LedgerLayout.CENTER_X, LedgerLayout.FOOTER_TOP + 4, INK, TextAlign.CENTER)
        }
        view.rows.take(LedgerLayout.ROWS).forEachIndexed { i, row ->
            val top = LedgerLayout.rowTop(i) + LedgerLayout.ROW_TEXT_OFFSET
            val labelX = if (row.icon != null) LedgerLayout.LABEL_X else LedgerLayout.LABEL_X_NO_ICON
            val valueRight = if (row.badge != null) LedgerLayout.VALUE_RIGHT_BADGE else LedgerLayout.VALUE_RIGHT
            val valueWidth = if (row.value.isEmpty()) 0 else measure(row.value) + 4
            textAt(fit(row.label, valueRight - labelX - valueWidth), labelX, top, row.labelColor)
            if (row.value.isNotEmpty()) textAt(fit(row.value, VALUE_MAX), valueRight, top, row.valueColor, TextAlign.RIGHT)
        }
        note(view.left, LedgerLayout.LEFT_X)
        note(view.right, LedgerLayout.RIGHT_X)
        if (view.subtitle.isNotEmpty()) textAt(fit(view.subtitle, 150), LedgerLayout.CENTER_X, LedgerLayout.SUBTITLE_TOP, FADED, TextAlign.CENTER)
        textAt(fit(view.title, TITLE_MAX), LedgerLayout.CENTER_X, LedgerLayout.TITLE_TOP, GOLD, TextAlign.CENTER)
    }

    private fun DisplayBuilder.note(lines: List<LedgerLine>, centerX: Int) {
        lines.take(LedgerLayout.NOTE_LINES).forEachIndexed { i, line ->
            textAt(fit(line.text, LedgerLayout.NOTE_WIDTH), centerX, LedgerLayout.NOTE_TOP + i * LedgerLayout.NOTE_LINE, line.color, TextAlign.CENTER)
        }
    }

    private fun withTooltip(item: ItemStack, tooltip: List<Component>): ItemStack {
        if (tooltip.isEmpty()) return item
        item.editMeta { meta ->
            meta.displayName(tooltip.first().decoration(TextDecoration.ITALIC, false))
            meta.lore(tooltip.drop(1).map { it.decoration(TextDecoration.ITALIC, false) })
        }
        return item
    }

    private fun tooltipItem(tooltip: List<Component>): ItemStack? {
        if (tooltip.isEmpty()) return null
        return withTooltip(ItemStack(Material.PAPER).apply { editMeta { it.setItemModel(EMPTY_MODEL) } }, tooltip)
    }

    /** Colours of the ledger art. */
    companion object {
        /** Ink on parchment. */
        val INK: TextColor = TextColor.color(0x3B2414)

        /** Faded ink. */
        val FADED: TextColor = TextColor.color(0x8A7350)

        /** Green ink (on, online). */
        val GREEN: TextColor = TextColor.color(0x2F6B2A)

        /** Red ink (off, warnings). */
        val RED: TextColor = TextColor.color(0x8A1D1D)

        /** Gold (ribbon). */
        val GOLD: TextColor = TextColor.color(0xF2CC5A)

        /** Light text on wood. */
        val LIGHT: TextColor = TextColor.color(0xF1E3C0)

        private val EMPTY_MODEL = NamespacedKey("helix_guis", "empty")
        private const val VALUE_MAX = 70
        private const val TITLE_MAX = 116
    }
}
