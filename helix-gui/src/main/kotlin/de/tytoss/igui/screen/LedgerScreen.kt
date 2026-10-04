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
 * One line of the info note.
 *
 * @property text the text.
 * @property color its colour.
 */
data class LedgerLine(val text: String, val color: TextColor = LedgerScreen.INK)

/**
 * Colour of a [LedgerPill] (design concept §7 badge: red = new, green = online, grey = offline, gold = rank).
 *
 * @property file art file infix.
 * @property text text colour on the pill.
 * @property shadow text shadow colour, or null.
 */
enum class PillColor(val file: String, val text: TextColor, val shadow: TextColor?) {
    /** Count of new things. */
    RED("red", LedgerScreen.WHITE, LedgerScreen.INK),

    /** Online, on, success. */
    GREEN("green", LedgerScreen.WHITE, LedgerScreen.INK),

    /** Offline, inactive. */
    GREY("grey", LedgerScreen.INK, null),

    /** Rank, highlight. */
    GOLD("gold", LedgerScreen.INK, null),
}

/**
 * A badge pill at the right end of a row.
 *
 * @property text one short line.
 * @property color the pill colour.
 */
data class LedgerPill(val text: String, val color: PillColor)

/**
 * One list row.
 *
 * @property label text on the left.
 * @property value text on the right (e.g. a setting's value); ignored when [pill] is set.
 * @property labelColor colour of the label.
 * @property valueColor colour of the value.
 * @property icon an item shown in the row's first cell (e.g. a player head), or null.
 * @property tooltip tooltip lines when hovering the row (first line = title).
 * @property badge an animated badge in the row's last cell (the value moves left of it), or null.
 * @property pill a badge pill instead of the plain value, or null.
 * @property selected whether the row is selected (gold strip).
 */
data class LedgerRow(
    val label: String,
    val value: String = "",
    val labelColor: TextColor = LedgerScreen.INK,
    val valueColor: TextColor = LedgerScreen.INK,
    val icon: ItemStack? = null,
    val tooltip: List<Component> = emptyList(),
    val badge: LedgerBadge? = null,
    val pill: LedgerPill? = null,
    val selected: Boolean = false,
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
 * Button styles of the design concept (§7), in glyph order.
 *
 * @property file art file infix.
 * @property text label colour.
 * @property shadow label shadow colour, or null.
 */
enum class ButtonStyle(val file: String, val text: TextColor, val shadow: TextColor?) {
    /** Emerald: confirm, choose, buy. */
    PRIMARY("primary", LedgerScreen.WHITE, LedgerScreen.INK),

    /** Gold: selected option or tab. */
    ACTIVE("active", LedgerScreen.INK, null),

    /** Parchment: neutral actions. */
    SECONDARY("secondary", LedgerScreen.INK, null),

    /** Ribbon red: remove, ban, dissolve (always ask back). */
    DANGER("danger", LedgerScreen.WHITE, LedgerScreen.INK),

    /** Grey: not available (the tooltip/note says why). */
    DISABLED("disabled", LedgerScreen.DISABLED_TEXT, null),
}

/**
 * A footer button.
 *
 * @property label short label (left button 36 px, right button 54 px wide).
 * @property style the style.
 */
data class LedgerButton(val label: String, val style: ButtonStyle = ButtonStyle.SECONDARY)

/**
 * The one calm life sign on the desk (design concept §16).
 *
 * @property sprite the sprite drawn.
 */
enum class LifeSign(val sprite: ScreenSprite) {
    /** A flickering candle on the book stack. */
    CANDLE(LedgerLayout.CANDLE),

    /** A quill writing on the paper of the book stack (letters, editors). */
    QUILL(LedgerLayout.QUILL),
}

/**
 * An empty state: a villager head and a sentence in their voice (design concept §7, never just "no entries").
 *
 * @property lines the sentence (wrapped automatically).
 * @property speaker villager id ([Villagers.IDS]).
 */
data class LedgerEmpty(val lines: List<String>, val speaker: String = Villagers.NARRATOR)

/**
 * What a ledger shows to one player.
 *
 * @property title text on the ribbon.
 * @property subtitle text under the ribbon.
 * @property rows rows of the current page (max. 8).
 * @property page current page (0-based).
 * @property pages page count.
 * @property detail lines of the info note on the right.
 * @property back whether the back arrow is shown (bottom left).
 * @property leftButton footer button over columns 1–2, or null.
 * @property rightButton footer button over columns 6–8, or null.
 * @property lifeSign the life sign on the desk.
 * @property empty what to show when [rows] is empty, or null.
 * @property left former left note (v1); shown above [right] when [detail] is empty.
 * @property right former name of [detail] (v1); used when [detail] is empty.
 * @property buttons former footer labels (v1): index 0 → [leftButton], 1 → [rightButton] (primary).
 * @property quill former quill flag (v1): true → [LifeSign.QUILL].
 */
data class LedgerView(
    val title: String,
    val subtitle: String = "",
    val rows: List<LedgerRow> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    val detail: List<LedgerLine> = emptyList(),
    val back: Boolean = false,
    val leftButton: LedgerButton? = null,
    val rightButton: LedgerButton? = null,
    val lifeSign: LifeSign = LifeSign.CANDLE,
    val empty: LedgerEmpty? = null,
    val left: List<LedgerLine> = emptyList(),
    val right: List<LedgerLine> = emptyList(),
    val buttons: List<String?> = listOf(null, null),
    val quill: Boolean = false,
) {
    /** The note lines actually shown (v2 [detail], else the v1 notes). */
    val noteLines: List<LedgerLine>
        get() = detail.ifEmpty { if (left.isEmpty()) right else left + LedgerLine("") + right }

    /** The left button actually shown. */
    val shownLeft: LedgerButton? get() = leftButton ?: buttons.getOrNull(0)?.let { LedgerButton(it) }

    /** The right button actually shown. */
    val shownRight: LedgerButton? get() = rightButton ?: buttons.getOrNull(1)?.let { LedgerButton(it, ButtonStyle.PRIMARY) }

    /** The life sign actually shown. */
    val shownLifeSign: LifeSign get() = if (quill) LifeSign.QUILL else lifeSign
}

/**
 * The shared **ledger** screen ("Pergament-Buch", see [LedgerLayout]) for list menus like the profile, letters or
 * staff tools. Consumers keep their own state, put the current [LedgerView] per player with [show] and re-render the
 * GUI; clicks come back as row index, button index, page step or back.
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
     * @param onButton footer button 0 (left) or 1 (right) was clicked (not for disabled buttons).
     * @param onPage a page arrow was clicked (-1 / +1).
     * @param onBack the back arrow was clicked.
     */
    fun install(
        page: GuiPageBuilder,
        onRow: suspend (GuiClickContext, Int) -> Unit,
        onButton: suspend (GuiClickContext, Int) -> Unit = { _, _ -> },
        onPage: suspend (GuiClickContext, Int) -> Unit = { _, _ -> },
        onBack: suspend (GuiClickContext) -> Unit = { _ -> },
    ) {
        val grid = GuiGrid(6, true)
        page.cancelAllInteractions = true
        page.title { ctx -> views[ctx.player.uniqueId]?.let { draw(it) } }
        val lifeSigns = LifeSign.entries.associateWith { SpriteItems.of(LedgerLayout.SPRITE_NAMESPACE, it.sprite) }
        page.item(grid.slot(LedgerLayout.CANDLE.column, LedgerLayout.CANDLE.row)) { ctx ->
            lifeSigns[views[ctx.player.uniqueId]?.shownLifeSign ?: LifeSign.CANDLE]?.clone()
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
                val view = views[ctx.player.uniqueId] ?: return@onClick
                val button = if (b == 0) view.shownLeft else view.shownRight
                if (button != null && button.style != ButtonStyle.DISABLED) onButton(ctx, b)
            }
        }
        page.onClick(grid.slot(LedgerLayout.BACK_COLUMN, LedgerLayout.FOOTER_ROW)) { ctx ->
            if (views[ctx.player.uniqueId]?.back == true) onBack(ctx)
        }
        page.onClick(grid.slot(LedgerLayout.PREV_COLUMN, LedgerLayout.FOOTER_ROW)) { ctx ->
            val view = views[ctx.player.uniqueId] ?: return@onClick
            if (view.page > 0) onPage(ctx, -1)
        }
        page.onClick(grid.slot(LedgerLayout.NEXT_COLUMN, LedgerLayout.FOOTER_ROW)) { ctx ->
            val view = views[ctx.player.uniqueId] ?: return@onClick
            if (view.page < view.pages - 1) onPage(ctx, 1)
        }
    }

    private fun DisplayBuilder.draw(view: LedgerView) {
        val font = Key.key(LedgerLayout.FONT)
        ScreenFonts.tiles(LedgerLayout.BG_W, LedgerLayout.BG_H).forEachIndexed { i, tile ->
            glyphAt(LedgerLayout.BG_X + tile.x, font, LedgerLayout.BG_TILE + i, tile.width + 1)
        }
        val arrow = LedgerLayout.ARROW_SIZE + 1
        if (view.back) glyphAt(LedgerLayout.cellX(LedgerLayout.BACK_COLUMN), font, LedgerLayout.ARROW_BACK, arrow)
        if (view.pages > 1) {
            val prev = if (view.page > 0) LedgerLayout.ARROW_LEFT else LedgerLayout.ARROW_LEFT_OFF
            val next = if (view.page < view.pages - 1) LedgerLayout.ARROW_RIGHT else LedgerLayout.ARROW_RIGHT_OFF
            glyphAt(LedgerLayout.cellX(LedgerLayout.PREV_COLUMN), font, prev, arrow)
            glyphAt(LedgerLayout.cellX(LedgerLayout.NEXT_COLUMN), font, next, arrow)
            textAt("${view.page + 1}", LedgerLayout.cellX(LedgerLayout.PAGE_COLUMN) + 9, LedgerLayout.FOOTER_TOP + 5, INK, TextAlign.CENTER)
        }
        listOf(view.shownLeft, view.shownRight).forEachIndexed { b, button ->
            if (button == null) return@forEachIndexed
            val x = LedgerLayout.cellX(LedgerLayout.BUTTON_COLUMNS[b].first)
            val width = if (b == 0) LedgerLayout.BUTTON_SMALL_W else LedgerLayout.BUTTON_WIDE_W
            val glyph = (if (b == 0) LedgerLayout.BUTTON_SMALL else LedgerLayout.BUTTON_WIDE) + button.style.ordinal
            glyphAt(x, font, glyph, width + 1)
            val label = fit(button.label, width - 6)
            textAt(label, x + width / 2, LedgerLayout.FOOTER_TOP + 5, button.style.text, TextAlign.CENTER, button.style.shadow)
        }
        view.rows.take(LedgerLayout.ROWS).forEachIndexed { i, row -> row(i, row, font) }
        if (view.rows.isEmpty()) view.empty?.let { empty(it, font) }
        view.noteLines.take(LedgerLayout.NOTE_LINES).forEachIndexed { i, line ->
            val top = LedgerLayout.NOTE_TOP + i * LedgerLayout.NOTE_LINE
            textAt(fit(line.text, LedgerLayout.NOTE_WIDTH), LedgerLayout.NOTE_X, top, line.color, TextAlign.CENTER)
        }
        if (view.subtitle.isNotEmpty()) {
            textAt(fit(view.subtitle, SUBTITLE_MAX), LedgerLayout.CENTER_X, LedgerLayout.SUBTITLE_TOP, FADED, TextAlign.CENTER)
        }
        textAt(fit(view.title, TITLE_MAX), LedgerLayout.CENTER_X, LedgerLayout.TITLE_TOP, GOLD, TextAlign.CENTER, INK)
    }

    private fun DisplayBuilder.row(i: Int, row: LedgerRow, font: Key) {
        if (row.selected) glyphAt(LedgerLayout.ROW_SELECTED_X, font, LedgerLayout.ROW_SELECTED + i, LedgerLayout.ROW_SELECTED_W + 1)
        val top = LedgerLayout.rowTop(i) + LedgerLayout.ROW_TEXT_OFFSET
        val labelX = if (row.icon != null) LedgerLayout.LABEL_X else LedgerLayout.LABEL_X_NO_ICON
        val valueRight = if (row.badge != null) LedgerLayout.VALUE_RIGHT_BADGE else LedgerLayout.VALUE_RIGHT
        val valueWidth = when {
            row.pill != null -> pill(i, row.pill, valueRight) + 4
            row.value.isEmpty() -> 0
            else -> {
                val value = fit(row.value, VALUE_MAX)
                textAt(value, valueRight, top, row.valueColor, TextAlign.RIGHT)
                measure(value) + 4
            }
        }
        textAt(fit(row.label, valueRight - labelX - valueWidth), labelX, top, row.labelColor)
    }

    /** Draws a pill ending at [right] in row [row] from cap and binary middle pieces; returns its width. */
    private fun DisplayBuilder.pill(row: Int, pill: LedgerPill, right: Int): Int {
        val font = Key.key(LedgerLayout.FONT)
        val text = fit(pill.text, PILL_TEXT_MAX)
        val inner = measure(text) + 2
        val width = inner + 2 * LedgerLayout.PILL_CAP
        val left = right - width
        val base = LedgerLayout.PILL + row * 32 + pill.color.ordinal * 8
        glyphAt(left, font, base, LedgerLayout.PILL_CAP + 1)
        var x = left + LedgerLayout.PILL_CAP
        var rest = inner
        for (p in LedgerLayout.PILL_PIECES.indices.reversed()) {
            val piece = LedgerLayout.PILL_PIECES[p]
            while (rest >= piece) {
                glyphAt(x, font, base + 2 + p, piece + 1)
                x += piece
                rest -= piece
            }
        }
        glyphAt(x, font, base + 1, LedgerLayout.PILL_CAP + 1)
        val top = LedgerLayout.rowTop(row) + LedgerLayout.ROW_TEXT_OFFSET
        textAt(text, left + width / 2 + 1, top, pill.color.text, TextAlign.CENTER)
        return width
    }

    private fun DisplayBuilder.empty(empty: LedgerEmpty, font: Key) {
        val head = LedgerLayout.EMPTY_HEAD_SIZE
        glyphAt(LedgerLayout.CENTER_X - head / 2, font, LedgerLayout.EMPTY_HEAD + Villagers.index(empty.speaker), head + 1)
        wrap(empty.lines, LedgerLayout.EMPTY_TEXT_WIDTH).take(EMPTY_MAX_LINES).forEachIndexed { i, line ->
            textAt(line, LedgerLayout.CENTER_X, LedgerLayout.EMPTY_TEXT_TOP + i * LedgerLayout.EMPTY_TEXT_LINE, INK, TextAlign.CENTER)
        }
    }

    private fun DisplayBuilder.wrap(lines: List<String>, width: Int): List<String> = lines.flatMap { line ->
        val out = mutableListOf<String>()
        var current = ""
        line.split(' ').forEach { word ->
            val next = if (current.isEmpty()) word else "$current $word"
            if (measure(next) > width && current.isNotEmpty()) {
                out += current
                current = word
            } else {
                current = next
            }
        }
        if (current.isNotEmpty()) out += current
        out
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

    /** Colours of the ledger (design tokens, see docs/design/tokens.json). */
    companion object {
        /** Ink on parchment (`ink`). */
        val INK: TextColor = TextColor.color(0x2A160A)

        /** Faded ink (`wood` light) for secondary text. */
        val FADED: TextColor = TextColor.color(0x7A4A24)

        /** Green ink (`emerald` dark: on, online). */
        val GREEN: TextColor = TextColor.color(0x2A8A3E)

        /** Red ink (`ribbon` dark: off, warnings). */
        val RED: TextColor = TextColor.color(0x9A2018)

        /** Gold (`gold` base: title, selection, reward). */
        val GOLD: TextColor = TextColor.color(0xFFCF4A)

        /** Parchment (`parchment` base: text on wood). */
        val LIGHT: TextColor = TextColor.color(0xF1E2C2)

        /** White (text on red/green/diamond, always with an ink shadow). */
        val WHITE: TextColor = TextColor.color(0xFFFFFF)

        /** Text on disabled surfaces (`disabled` dark). */
        val DISABLED_TEXT: TextColor = TextColor.color(0x6E6252)

        private val EMPTY_MODEL = NamespacedKey("helix_guis", "empty")
        private const val VALUE_MAX = 70
        private const val TITLE_MAX = 132
        private const val SUBTITLE_MAX = 150
        private const val PILL_TEXT_MAX = 60
        private const val EMPTY_MAX_LINES = 6
    }
}
