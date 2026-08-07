package de.tytoss.igui.pagination

import de.tytoss.igui.gui.GuiClickContext
import de.tytoss.igui.gui.GuiPageBuilder
import de.tytoss.igui.gui.GuiRenderContext
import de.tytoss.igui.slot.SlotSelection
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * DSL for [paginate]: declares where a page's content items come from, how
 * to render and react to clicks on them, and hides all per-viewer paging
 * state (current page, loaded items) behind the built-in `prepare`/`onClick`
 * wiring [paginate] installs on the enclosing page.
 *
 * @param T the item type being paginated.
 */
class PaginationBuilder<T> internal constructor(private val contentSlots: IntArray) {
    /** Slot that moves to the previous page when clicked; must be set. */
    var previousSlot: Int = -1

    /** Slot that moves to the next page when clicked; must be set. */
    var nextSlot: Int = -1
    private var source: (suspend (Player) -> List<T>)? = null
    private var renderer: ((GuiRenderContext, T) -> ItemStack)? = null
    private var clickHandler: (suspend (GuiClickContext, T) -> Unit)? = null
    private val states = ConcurrentHashMap<UUID, PaginationSession<T>>()
    private val loadVersions = ConcurrentHashMap<UUID, Long>()
    private val versionSequence = AtomicLong()

    /**
     * Uses a fixed, shared list of items for every viewer.
     *
     * @param items the full (unpaginated) list of items.
     */
    fun items(items: List<T>) {
        val snapshot = items.toList()
        source = { snapshot }
    }

    /**
     * Loads a per-viewer list of items every time the page is prepared
     * (opened or refreshed with `reloadData = true`).
     *
     * @param provider produces the full (unpaginated) list of items for a given player.
     */
    fun source(provider: suspend (Player) -> List<T>) {
        source = provider
    }

    /**
     * Declares how to render a single item into its slot.
     *
     * @param renderer produces the item stack for an item at a given render context.
     */
    fun render(renderer: (GuiRenderContext, T) -> ItemStack) {
        this.renderer = renderer
    }

    /**
     * Declares what happens when a content slot (not the previous/next
     * slots) is clicked.
     *
     * @param handler invoked with the click context and the clicked item.
     */
    fun onClick(handler: suspend (GuiClickContext, T) -> Unit) {
        clickHandler = handler
    }

    internal fun validate() {
        require(contentSlots.isNotEmpty()) { "Pagination requires at least one content slot" }
        require(previousSlot >= 0) { "Pagination previousSlot must be configured" }
        require(nextSlot >= 0) { "Pagination nextSlot must be configured" }
        requireNotNull(source) { "Pagination requires items or a source" }
        requireNotNull(renderer) { "Pagination requires an item renderer" }
    }

    internal suspend fun prepare(player: Player) {
        val version = versionSequence.incrementAndGet()
        loadVersions[player.uniqueId] = version
        val items = requireNotNull(source)(player)
        if (loadVersions[player.uniqueId] == version) {
            states[player.uniqueId] = PaginationSession(items.toList(), 1)
        }
    }

    internal fun render(context: GuiRenderContext, slotIndex: Int): ItemStack? {
        val state = states[context.player.uniqueId] ?: return null
        val item = state.items.getOrNull((state.page - 1) * contentSlots.size + slotIndex) ?: return null
        return requireNotNull(renderer)(context, item)
    }

    internal suspend fun click(context: GuiClickContext) {
        val state = states[context.player.uniqueId] ?: return
        val slotIndex = contentSlots.indexOf(context.slot)
        if (slotIndex < 0) return
        val item = state.items.getOrNull((state.page - 1) * contentSlots.size + slotIndex) ?: return
        clickHandler?.invoke(context, item)
    }

    internal suspend fun move(context: GuiClickContext, delta: Int) {
        val state = states[context.player.uniqueId] ?: return
        val pages = pageCount(state.items.size, contentSlots.size)
        val target = (state.page + delta).coerceIn(1, pages)
        if (target == state.page) return
        state.page = target
        context.gui.refresh(context.player, reloadData = false)
    }

    internal fun cleanup(playerId: UUID) {
        states.remove(playerId)
        loadVersions.remove(playerId)
    }

    private data class PaginationSession<T>(val items: List<T>, var page: Int)
}

/**
 * Turns a slot selection into a self-managing, paginated content grid:
 * wires up the page's `prepare` hook to load items, binds each slot in
 * [selection] to render one item per page, and binds [PaginationBuilder.previousSlot]/
 * [PaginationBuilder.nextSlot] to page navigation.
 *
 * @param T the item type being paginated.
 * @param selection the slots used to display one page's worth of items.
 * @param block configures the item source, renderer, click handler and navigation slots.
 */
fun <T> GuiPageBuilder.paginate(
    selection: SlotSelection,
    block: PaginationBuilder<T>.() -> Unit,
) {
    val slots = ArrayList<Int>()
    selection.forEach(54, slots::add)
    val pagination = PaginationBuilder<T>(slots.toIntArray()).apply(block)
    pagination.validate()
    prepare(pagination::prepare)
    cleanup(pagination::cleanup)
    slots.forEachIndexed { index, slot -> item(slot) { context -> pagination.render(context, index) } }
    onClick(selection = selection, handler = pagination::click)
    onClick(pagination.previousSlot) { context -> pagination.move(context, -1) }
    onClick(pagination.nextSlot) { context -> pagination.move(context, 1) }
}
