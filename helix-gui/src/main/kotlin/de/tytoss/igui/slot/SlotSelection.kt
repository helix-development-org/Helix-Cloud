package de.tytoss.igui.slot

/**
 * A reusable, composable description of a set of inventory slots, used to
 * bind item renderers/click handlers to more than one slot at once (see
 * [de.tytoss.igui.gui.GuiPageBuilder.onClick] and [de.tytoss.igui.pagination.paginate]).
 * Selections can be combined with [plus], [minus] and [intersect]. Slot
 * indices follow chest layout: row-major, 9 columns per row.
 */
sealed interface SlotSelection {
    /**
     * Invokes [action] once for each slot in this selection, in an
     * implementation-defined order.
     *
     * @param inventorySize size of the inventory the selection is applied
     *  to, used to bounds-check slots.
     * @param action invoked with each selected slot index.
     */
    fun forEach(inventorySize: Int, action: (Int) -> Unit)

    /** A single slot. */
    data class Single(val slot: Int) : SlotSelection {
        init {
            require(slot >= 0) { "Slot must not be negative" }
        }

        override fun forEach(inventorySize: Int, action: (Int) -> Unit) {
            require(slot < inventorySize) { "Slot $slot is outside inventory size $inventorySize" }
            action(slot)
        }
    }

    /** All slots in the rectangle spanned between two corner slots, inclusive. */
    data class Rectangle(val first: Int, val second: Int) : SlotSelection {
        init {
            require(first >= 0 && second >= 0) { "Rectangle slots must not be negative" }
        }

        override fun forEach(inventorySize: Int, action: (Int) -> Unit) {
            require(first < inventorySize && second < inventorySize) {
                "Rectangle $first..$second is outside inventory size $inventorySize"
            }
            val firstRow = first / COLUMNS
            val secondRow = second / COLUMNS
            val firstColumn = first % COLUMNS
            val secondColumn = second % COLUMNS
            for (row in minOf(firstRow, secondRow)..maxOf(firstRow, secondRow)) {
                for (column in minOf(firstColumn, secondColumn)..maxOf(firstColumn, secondColumn)) {
                    action(row * COLUMNS + column)
                }
            }
        }
    }

    /** All 9 slots of a chest row (1-based, 1..6). */
    data class Row(val row: Int) : SlotSelection {
        init {
            require(row in 1..6) { "Chest row must be in 1..6" }
        }

        override fun forEach(inventorySize: Int, action: (Int) -> Unit) {
            val first = (row - 1) * COLUMNS
            require(first < inventorySize) { "Row $row is outside inventory size $inventorySize" }
            for (slot in first until first + COLUMNS) action(slot)
        }
    }

    /** Every slot of a chest column (1-based, 1..9), across all rows of the inventory. */
    data class Column(val column: Int) : SlotSelection {
        init {
            require(column in 1..9) { "Chest column must be in 1..9" }
        }

        override fun forEach(inventorySize: Int, action: (Int) -> Unit) {
            var slot = column - 1
            while (slot < inventorySize) {
                action(slot)
                slot += COLUMNS
            }
        }
    }

    /** Only the outer ring of slots (top/bottom rows and left/right columns) of the rectangle spanned by two corners. */
    data class Border(val first: Int, val second: Int) : SlotSelection {
        override fun forEach(inventorySize: Int, action: (Int) -> Unit) {
            val selected = ArrayList<Int>()
            Rectangle(first, second).forEach(inventorySize, selected::add)
            val minRow = minOf(first / COLUMNS, second / COLUMNS)
            val maxRow = maxOf(first / COLUMNS, second / COLUMNS)
            val minColumn = minOf(first % COLUMNS, second % COLUMNS)
            val maxColumn = maxOf(first % COLUMNS, second % COLUMNS)
            selected.forEach { slot ->
                val row = slot / COLUMNS
                val column = slot % COLUMNS
                if (row == minRow || row == maxRow || column == minColumn || column == maxColumn) action(slot)
            }
        }
    }

    /** The union, intersection or difference of two selections, built by [plus], [minus] and [intersect]. */
    class Compound internal constructor(
        private val left: SlotSelection,
        private val right: SlotSelection,
        private val operation: Operation,
    ) : SlotSelection {
        override fun forEach(inventorySize: Int, action: (Int) -> Unit) {
            val leftSlots = BooleanArray(inventorySize)
            val rightSlots = BooleanArray(inventorySize)
            left.forEach(inventorySize) { leftSlots[it] = true }
            right.forEach(inventorySize) { rightSlots[it] = true }
            for (slot in 0 until inventorySize) {
                val selected = when (operation) {
                    Operation.UNION -> leftSlots[slot] || rightSlots[slot]
                    Operation.INTERSECTION -> leftSlots[slot] && rightSlots[slot]
                    Operation.DIFFERENCE -> leftSlots[slot] && !rightSlots[slot]
                }
                if (selected) action(slot)
            }
        }

        internal enum class Operation {
            UNION,
            INTERSECTION,
            DIFFERENCE,
        }
    }

    /** An explicit, arbitrary set of slots, built by [slots]. Duplicate indices are collapsed. */
    class Set internal constructor(slots: IntArray) : SlotSelection {
        private val values: IntArray = slots.distinct().toIntArray()

        override fun forEach(inventorySize: Int, action: (Int) -> Unit) {
            values.forEach { slot ->
                require(slot in 0 until inventorySize) { "Slot $slot is outside inventory size $inventorySize" }
                action(slot)
            }
        }
    }

    private companion object {
        const val COLUMNS: Int = 9
    }
}

/**
 * A selection matching a single slot.
 *
 * @param index the slot index.
 */
fun slot(index: Int): SlotSelection = SlotSelection.Single(index)

/**
 * A selection matching the rectangle between two corner slots, inclusive.
 *
 * @receiver one corner slot index.
 * @param other the opposite corner slot index.
 */
infix fun Int.rectTo(other: Int): SlotSelection = SlotSelection.Rectangle(this, other)

/**
 * A selection matching a whole chest row.
 *
 * @param index 1-based row number, 1..6.
 */
fun row(index: Int): SlotSelection = SlotSelection.Row(index)

/**
 * A selection matching a whole chest column.
 *
 * @param index 1-based column number, 1..9.
 */
fun column(index: Int): SlotSelection = SlotSelection.Column(index)

/**
 * A selection matching only the outer ring of the rectangle between two corner slots.
 *
 * @param first one corner slot index.
 * @param second the opposite corner slot index.
 */
fun border(first: Int, second: Int): SlotSelection = SlotSelection.Border(first, second)

/**
 * Combines two selections into one matching any slot selected by either.
 *
 * @receiver one of the selections to combine.
 * @param other the other selection to combine.
 */
operator fun SlotSelection.plus(other: SlotSelection): SlotSelection =
    SlotSelection.Compound(this, other, SlotSelection.Compound.Operation.UNION)

/**
 * Combines two selections into one matching slots selected by the receiver but not by [other].
 *
 * @receiver the selection to subtract from.
 * @param other the selection whose slots are excluded.
 */
operator fun SlotSelection.minus(other: SlotSelection): SlotSelection =
    SlotSelection.Compound(this, other, SlotSelection.Compound.Operation.DIFFERENCE)

/**
 * Combines two selections into one matching only slots selected by both.
 *
 * @receiver one of the selections to intersect.
 * @param other the other selection to intersect.
 */
infix fun SlotSelection.intersect(other: SlotSelection): SlotSelection =
    SlotSelection.Compound(this, other, SlotSelection.Compound.Operation.INTERSECTION)

/**
 * A selection matching an explicit set of slots.
 *
 * @param indices the slot indices to select.
 */
fun slots(vararg indices: Int): SlotSelection = SlotSelection.Set(indices)

/**
 * Converts 1-based chest row/column coordinates into a raw slot index.
 *
 * @param row 1-based row number, 1..6.
 * @param column 1-based column number, 1..9.
 * @return the corresponding raw slot index.
 */
fun chestSlot(row: Int, column: Int): Int {
    require(row in 1..6) { "Chest row must be in 1..6" }
    require(column in 1..9) { "Chest column must be in 1..9" }
    return (row - 1) * 9 + column - 1
}
