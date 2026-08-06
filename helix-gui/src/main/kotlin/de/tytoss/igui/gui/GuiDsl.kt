package de.tytoss.igui.gui

import de.tytoss.igui.display.DisplayBuilder
import de.tytoss.igui.display.GuiFontConfiguration
import de.tytoss.igui.internal.GuiRuntime
import de.tytoss.igui.slot.SlotSelection
import de.tytoss.igui.slot.slot
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Restricts the GUI DSL's builder receivers so nested blocks cannot accidentally call an outer builder. */
@DslMarker
annotation class IGuiDsl

/**
 * DSL entry point for declaring a GUI, as passed to [de.tytoss.igui.IGui.gui].
 * Declares the inventory size, which page opens by default, and one or more
 * [page]s.
 */
@IGuiDsl
class GuiDefinitionBuilder internal constructor(
    private val fonts: GuiFontConfiguration,
) {
    /** Number of chest rows (1..6); the inventory has `rows * 9` slots. */
    var rows: Int = 3

    /** Id of the page opened by default when no page is specified. */
    var landingPage: String = "main"

    private val pages = LinkedHashMap<String, GuiPageBuilder>()
    private var closeHandler: GuiCloseHandler? = null

    /**
     * Declares a page of this GUI.
     *
     * @param id unique id for the page within this GUI.
     * @param block DSL block declaring the page's title, items and handlers.
     */
    fun page(id: String, block: GuiPageBuilder.() -> Unit) {
        require(id.isNotBlank()) { "Page id must not be blank" }
        require(id !in pages) { "Duplicate page '$id'" }
        pages[id] = GuiPageBuilder(fonts).apply(block)
    }

    /**
     * Registers a handler invoked whenever a viewer closes this GUI, for any page.
     *
     * @param handler invoked with the closing player and last-viewed page.
     */
    fun onClose(handler: GuiCloseHandler) {
        closeHandler = handler
    }

    internal fun build(runtime: GuiRuntime, id: String): GuiDefinition {
        require(id.isNotBlank()) { "GUI id must not be blank" }
        require(rows in 1..6) { "GUI rows must be in 1..6" }
        require(pages.isNotEmpty()) { "GUI '$id' requires at least one page" }
        require(landingPage in pages) { "Landing page '$landingPage' is not defined" }
        val inventorySize = rows * 9
        val compiled = LinkedHashMap<String, GuiPage>(pages.size)
        pages.forEach { (pageId, builder) -> compiled[pageId] = builder.build(pageId, inventorySize) }
        return GuiDefinition(runtime, id, rows, landingPage, compiled, closeHandler)
    }
}

/**
 * DSL for declaring a single page of a GUI: its title, static and dynamic
 * items, click handlers and lifecycle hooks.
 */
@IGuiDsl
class GuiPageBuilder internal constructor(
    private val fonts: GuiFontConfiguration,
) {
    private var title: Component? = null
    private var titleRenderer: ((GuiRenderContext) -> Component)? = null
    private val items = arrayOfNulls<ItemStack>(54)
    private val itemRenderers = arrayOfNulls<(GuiRenderContext) -> ItemStack?>(54)
    private val handlerBindings = ArrayList<Pair<SlotSelection, GuiCompiledClick>>()
    private var openHandler: (suspend (Player) -> Unit)? = null
    private var prepareHandler: (suspend (Player) -> Unit)? = null
    private val cleanupHandlers = ArrayList<(UUID) -> Unit>()
    private var deniedHandler: (suspend (Player, GuiAccessDenial) -> Unit)? = null

    /** Whether all inventory clicks/drags on this page are cancelled by default. Defaults to `true`. */
    var cancelAllInteractions: Boolean = true

    /** Permission node required to open this page; `null` (the default) allows everyone. */
    var permission: String? = null

    /**
     * Declares a static title, rendered once when the page is built.
     *
     * @param block builds the title via [DisplayBuilder].
     */
    fun title(block: DisplayBuilder.() -> Unit) {
        check(title == null && titleRenderer == null) { "A page title can only be defined once" }
        title = DisplayBuilder(fonts).apply(block).build().component
    }

    /**
     * Declares a per-viewer title, re-rendered on every open/refresh.
     *
     * @param block builds the title via [DisplayBuilder], given the render context.
     */
    fun title(block: DisplayBuilder.(GuiRenderContext) -> Unit) {
        check(title == null && titleRenderer == null) { "A page title can only be defined once" }
        titleRenderer = { context -> DisplayBuilder(fonts).apply { block(context) }.build().component }
    }

    /**
     * Places a static item template in a slot, cloned into every rendered
     * instance of the page.
     *
     * @param slot the slot index (0..53) to place the item in.
     * @param item the item template.
     */
    fun item(slot: Int, item: ItemStack) {
        require(slot in items.indices) { "Slot $slot is outside 0..53" }
        items[slot] = item.clone()
    }

    /**
     * Places a per-viewer dynamic item in a slot, re-invoked on every render.
     *
     * @param slot the slot index (0..53) to place the item in.
     * @param renderer produces the item for a given render context, or `null` to leave the slot empty.
     */
    fun item(slot: Int, renderer: (GuiRenderContext) -> ItemStack?) {
        require(slot in items.indices) { "Slot $slot is outside 0..53" }
        itemRenderers[slot] = renderer
    }

    /**
     * Registers a click handler for every slot in [selection].
     *
     * @param selection the slots to bind the handler to.
     * @param permission permission node required to trigger the handler; `null` allows everyone.
     * @param cooldownMillis minimum time between triggers per player, in milliseconds.
     * @param handler invoked on a qualifying click.
     */
    fun onClick(
        selection: SlotSelection,
        permission: String? = null,
        cooldownMillis: Long = 0,
        handler: GuiClickHandler,
    ) {
        require(cooldownMillis >= 0) { "Cooldown must not be negative" }
        handlerBindings += selection to GuiCompiledClick(handler, permission, cooldownMillis)
    }

    /**
     * Registers a click handler for a single slot.
     *
     * @param slot the slot index to bind the handler to.
     * @param handler invoked on click.
     */
    fun onClick(slot: Int, handler: GuiClickHandler): Unit =
        onClick(selection = slot(slot), handler = handler)

    /**
     * Registers a click handler for a single slot, with permission and cooldown.
     *
     * @param slot the slot index to bind the handler to.
     * @param permission permission node required to trigger the handler; `null` allows everyone.
     * @param cooldownMillis minimum time between triggers per player, in milliseconds.
     * @param handler invoked on a qualifying click.
     */
    fun onClick(
        slot: Int,
        permission: String? = null,
        cooldownMillis: Long = 0,
        handler: GuiClickHandler,
    ): Unit = onClick(
        selection = slot(slot),
        permission = permission,
        cooldownMillis = cooldownMillis,
        handler = handler,
    )

    /**
     * Registers a handler invoked when a click or page open is denied by
     * [permission] or a slot's cooldown.
     *
     * @param handler invoked with the denied player and the reason.
     */
    fun onDenied(handler: suspend (Player, GuiAccessDenial) -> Unit) {
        deniedHandler = handler
    }

    /**
     * Registers a handler invoked right after this page's inventory is opened for a player.
     *
     * @param handler invoked with the viewing player.
     */
    fun onOpen(handler: suspend (Player) -> Unit) {
        openHandler = handler
    }

    /**
     * Registers a handler invoked before the page is (re-)rendered, to load
     * or refresh per-viewer data used by dynamic item renderers.
     *
     * @param handler invoked with the viewing player.
     */
    fun prepare(handler: suspend (Player) -> Unit) {
        prepareHandler = handler
    }

    internal fun cleanup(handler: (UUID) -> Unit) {
        cleanupHandlers += handler
    }

    internal fun build(id: String, inventorySize: Int): GuiPage {
        require(title != null || titleRenderer != null) { "Page '$id' requires a title" }
        for (index in inventorySize until items.size) {
            require(items[index] == null) {
                "Page '$id' item slot $index is outside inventory size $inventorySize"
            }
            require(itemRenderers[index] == null) {
                "Page '$id' dynamic item slot $index is outside inventory size $inventorySize"
            }
        }
        val handlers = arrayOfNulls<GuiCompiledClick>(inventorySize)
        handlerBindings.forEach { (selection, handler) ->
            selection.forEach(inventorySize) { selected -> handlers[selected] = handler }
        }
        val itemTemplates = Array<ItemStack?>(inventorySize) { index -> items[index]?.clone() }
        val compiledRenderers = Array<(GuiRenderContext) -> ItemStack?>(inventorySize) { index ->
            itemRenderers[index] ?: { itemTemplates[index]?.clone() }
        }
        return GuiPage(
            id,
            title,
            titleRenderer,
            itemTemplates,
            compiledRenderers,
            handlers,
            cancelAllInteractions,
            openHandler,
            prepareHandler,
            cleanupHandlers.toList(),
            permission,
            deniedHandler,
        )
    }
}
