package de.tytoss.igui.gui

import de.tytoss.igui.internal.GuiRuntime
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * A compiled, registered GUI: the immutable result of
 * [de.tytoss.igui.gui.GuiDefinitionBuilder.build] as declared through
 * [de.tytoss.igui.IGui.gui]. Pages, items and handlers are fixed once built;
 * per-viewer state (which page, item contents) lives in the runtime instead.
 *
 * @property id the GUI's unique id, as passed to [de.tytoss.igui.IGui.gui].
 */
class GuiDefinition internal constructor(
    internal val runtime: GuiRuntime,
    val id: String,
    internal val rows: Int,
    internal val landingPage: String,
    internal val compiledPages: Map<String, GuiPage>,
    internal val closeHandler: GuiCloseHandler?,
) {
    /** Ids of all pages declared on this GUI. */
    val pages: Set<String> = compiledPages.keys

    /**
     * Opens this GUI for a player, closing whatever inventory (GUI or
     * vanilla) they currently have open.
     *
     * @param player the player to open the GUI for.
     * @param pageId the page to open; defaults to the GUI's landing page.
     */
    suspend fun open(player: Player, pageId: String = landingPage) =
        runtime.open(player, this, pageId)

    /**
     * Re-renders the page a player currently has open, keeping them on it.
     * A no-op if the player is not currently viewing this GUI.
     *
     * @param player the viewer to refresh.
     * @param reloadData whether to re-invoke the page's prepare handler
     *  before re-rendering, or just redraw with existing data.
     */
    suspend fun refresh(player: Player, reloadData: Boolean = true) =
        runtime.refresh(player, this, reloadData)

    /** Refreshes this GUI for every player currently viewing any of its pages. */
    suspend fun refreshAll() = runtime.refresh(this)

    /**
     * Closes this GUI for a single player, if they currently have it open.
     *
     * @param player the viewer to close the GUI for.
     */
    suspend fun close(player: Player) = runtime.close(player, this)

    /** Closes this GUI for every player currently viewing it and unregisters it from the runtime. */
    suspend fun close() = runtime.close(this)
}

internal class GuiPage(
    val id: String,
    val title: Component?,
    val titleRenderer: ((GuiRenderContext) -> Component)?,
    val itemTemplates: Array<ItemStack?>,
    val itemRenderers: Array<(GuiRenderContext) -> ItemStack?>,
    val handlers: Array<GuiCompiledClick?>,
    val cancelAllInteractions: Boolean,
    val openHandler: (suspend (Player) -> Unit)?,
    val prepareHandler: (suspend (Player) -> Unit)?,
    val cleanupHandlers: List<(UUID) -> Unit>,
    val permission: String?,
    val deniedHandler: (suspend (Player, GuiAccessDenial) -> Unit)?,
)

internal data class GuiCompiledClick(
    val handler: GuiClickHandler,
    val permission: String?,
    val cooldownMillis: Long,
)

/** Why a click or page open was denied, passed to [de.tytoss.igui.gui.GuiPageBuilder.onDenied]. */
enum class GuiAccessDenial {
    /** The player lacked the permission node required for the page or slot. */
    PERMISSION,

    /** The slot's per-player cooldown had not yet elapsed. */
    COOLDOWN,
}
