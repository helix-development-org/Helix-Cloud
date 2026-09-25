package de.tytoss.igui.layout

/**
 * A cell of the GUI slot grid: [column] 0..8 from the left, [row] from the top.
 *
 * Rows are counted across the whole window. For a GUI with `rows` chest rows,
 * rows `0 until rows` are the chest, followed (when the player inventory is
 * part of the GUI) by the three main-inventory rows and finally the hotbar row.
 *
 * @property column zero-based column, 0..8.
 * @property row zero-based row across the whole window.
 */
data class GuiCell(val column: Int, val row: Int)

/**
 * Slot and pixel geometry of a chest GUI, optionally including the viewer's
 * player inventory (see [de.tytoss.igui.gui.GuiDefinitionBuilder.playerInventory]).
 *
 * Raw slots are numbered row by row across the whole window, so the raw slot
 * of a cell is always `row * 9 + column` — the same numbering Bukkit uses for
 * `InventoryClickEvent.rawSlot`. Pixel coordinates are relative to the
 * top-left corner of the GUI texture (176 px wide) and describe where the
 * client draws the 16x16 item of a slot; they are meant for positioning
 * title glyphs precisely over slots.
 *
 * @property rows number of chest rows (1..6).
 * @property includesPlayerInventory whether the player inventory rows belong to the GUI.
 */
class GuiGrid(val rows: Int, val includesPlayerInventory: Boolean = false) {
    init {
        require(rows in 1..MAX_CHEST_ROWS) { "GUI rows must be in 1..$MAX_CHEST_ROWS" }
    }

    /** Total number of grid rows (chest rows plus four player-inventory rows when included). */
    val totalRows: Int = rows + if (includesPlayerInventory) PLAYER_INVENTORY_ROWS else 0

    /** Number of addressable raw slots. */
    val slotCount: Int = totalRows * COLUMNS

    /** Number of raw slots that belong to the chest (top) inventory. */
    val chestSlotCount: Int = rows * COLUMNS

    /**
     * Raw slot of a cell.
     *
     * @param column zero-based column, 0..8.
     * @param row zero-based grid row.
     * @return the raw slot index.
     * @throws IllegalArgumentException if the cell lies outside this grid.
     */
    fun slot(column: Int, row: Int): Int {
        require(column in 0 until COLUMNS) { "Column $column is outside 0..${COLUMNS - 1}" }
        require(row in 0 until totalRows) { "Row $row is outside 0..${totalRows - 1}" }
        return row * COLUMNS + column
    }

    /**
     * Cell of a raw slot, or `null` when the slot lies outside this grid
     * (for example a click outside the window, raw slot -999).
     *
     * @param rawSlot the raw slot index.
     * @return the cell, or `null`.
     */
    fun cell(rawSlot: Int): GuiCell? =
        if (rawSlot in 0 until slotCount) GuiCell(rawSlot % COLUMNS, rawSlot / COLUMNS) else null

    /**
     * Whether a raw slot belongs to the player inventory part of this grid.
     *
     * @param rawSlot the raw slot index.
     * @return `true` for player-inventory slots of a grid that includes them.
     */
    fun isPlayerInventorySlot(rawSlot: Int): Boolean = rawSlot in chestSlotCount until slotCount

    /**
     * Index into `PlayerInventory.storageContents` (0..8 hotbar, 9..35 main
     * inventory) for a player-inventory raw slot.
     *
     * @param rawSlot the raw slot index.
     * @return the storage index, or `null` if [rawSlot] is not a player-inventory slot.
     */
    fun storageIndex(rawSlot: Int): Int? {
        if (!isPlayerInventorySlot(rawSlot)) return null
        val offset = rawSlot - chestSlotCount
        return if (offset < MAIN_INVENTORY_SLOTS) HOTBAR_SLOTS + offset else offset - MAIN_INVENTORY_SLOTS
    }

    /**
     * X pixel of the left edge of a slot's item area.
     *
     * @param column zero-based column.
     * @return the x coordinate relative to the GUI's left edge.
     */
    fun itemX(column: Int): Int = ITEM_LEFT + column * SLOT_SIZE

    /**
     * Y pixel of the top edge of a slot's item area, following the vanilla
     * chest layout: chest rows start at 18, the main inventory 13 px below the
     * last chest row and the hotbar 4 px below the main inventory.
     *
     * @param row zero-based grid row.
     * @return the y coordinate relative to the GUI's top edge.
     * @throws IllegalArgumentException if the row lies outside this grid.
     */
    fun itemY(row: Int): Int {
        require(row in 0 until totalRows) { "Row $row is outside 0..${totalRows - 1}" }
        return when {
            row < rows -> CHEST_TOP + row * SLOT_SIZE
            row < rows + MAIN_INVENTORY_ROWS -> CHEST_TOP + rows * SLOT_SIZE + MAIN_INVENTORY_GAP + (row - rows) * SLOT_SIZE
            else -> CHEST_TOP + rows * SLOT_SIZE + MAIN_INVENTORY_GAP + MAIN_INVENTORY_ROWS * SLOT_SIZE + HOTBAR_GAP
        }
    }

    /** Constants of the vanilla chest GUI layout and the title-glyph helpers. */
    companion object {
        /** Slots per row. */
        const val COLUMNS: Int = 9

        /** Largest chest GUI (double chest). */
        const val MAX_CHEST_ROWS: Int = 6

        /** Rows of the player inventory: three main rows plus the hotbar. */
        const val PLAYER_INVENTORY_ROWS: Int = 4

        /** Distance between two slots, in pixels. */
        const val SLOT_SIZE: Int = 18

        /** Title origin: the client draws the container title at this y (x is 8). */
        const val TITLE_Y: Int = 6

        /** Height above the baseline of a vanilla-sized glyph whose top sits on the title line. */
        private const val TITLE_ASCENT: Int = 7
        private const val ITEM_LEFT = 8
        private const val CHEST_TOP = 18
        private const val MAIN_INVENTORY_ROWS = 3
        private const val MAIN_INVENTORY_SLOTS = MAIN_INVENTORY_ROWS * COLUMNS
        private const val HOTBAR_SLOTS = COLUMNS
        private const val MAIN_INVENTORY_GAP = 13
        private const val HOTBAR_GAP = 4

        /**
         * Font `ascent` a bitmap glyph needs so its top edge lands on pixel
         * [top] of the GUI when used in the container title (`13 - top`; may be
         * negative). Resource packs bake this into the font provider, so every
         * distinct y position needs its own glyph.
         *
         * @param top target y of the glyph's top edge, relative to the GUI's top edge.
         * @return the ascent to put into the font provider.
         */
        fun ascentForTop(top: Int): Int = TITLE_Y + TITLE_ASCENT - top
    }
}

/**
 * A rectangular region of the slot grid, for multi-slot buttons, large
 * images or card spots: clicks anywhere inside resolve to a cell relative to
 * the region's top-left corner (see [de.tytoss.igui.gui.GuiClickContext.cellIn]).
 *
 * @property column left column of the region.
 * @property row top row of the region.
 * @property width number of columns (at least 1).
 * @property height number of rows (at least 1).
 */
data class GuiArea(val column: Int, val row: Int, val width: Int, val height: Int) {
    init {
        require(column >= 0 && row >= 0) { "Area origin must not be negative" }
        require(width >= 1 && height >= 1) { "Area size must be at least 1x1" }
        require(column + width <= GuiGrid.COLUMNS) { "Area exceeds the ${GuiGrid.COLUMNS} grid columns" }
    }

    /**
     * Whether [cell] lies inside this area.
     *
     * @param cell the grid cell.
     * @return `true` if inside.
     */
    operator fun contains(cell: GuiCell): Boolean =
        cell.column in column until column + width && cell.row in row until row + height

    /**
     * Position of [cell] relative to this area's top-left corner.
     *
     * @param cell the grid cell.
     * @return the relative cell, or `null` if [cell] is outside the area.
     */
    fun relative(cell: GuiCell): GuiCell? =
        if (cell in this) GuiCell(cell.column - column, cell.row - row) else null

    /** All raw slots covered by this area (row-major), using the grid numbering `row * 9 + column`. */
    fun slots(): IntArray = IntArray(width * height) { index ->
        (row + index / width) * GuiGrid.COLUMNS + column + index % width
    }

    /** The area as a [de.tytoss.igui.slot.SlotSelection], for binding items or click handlers. */
    fun selection(): de.tytoss.igui.slot.SlotSelection = de.tytoss.igui.slot.slots(*slots())
}
