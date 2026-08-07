package de.tytoss.igui.gui

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent

/** Handler invoked when a player clicks a bound slot, see [de.tytoss.igui.gui.GuiPageBuilder.onClick]. */
typealias GuiClickHandler = suspend (GuiClickContext) -> Unit

/** Handler invoked when a GUI is closed, see [de.tytoss.igui.gui.GuiDefinitionBuilder.onClose]. */
typealias GuiCloseHandler = suspend (GuiCloseContext) -> Unit

/**
 * Context passed to a [GuiClickHandler], giving access to the triggering
 * event, the clicking player and page navigation/input helpers.
 *
 * @property event the raw Bukkit click event; [de.tytoss.igui.internal.GuiRuntime]
 *  cancels it beforehand unless the page opts out.
 * @property player the player who clicked.
 * @property gui the GUI definition being viewed.
 * @property pageId the id of the page the click happened on.
 */
class GuiClickContext internal constructor(
    val event: InventoryClickEvent,
    val player: Player,
    val gui: GuiDefinition,
    val pageId: String,
    private val pageOpener: suspend (String) -> Unit,
    private val inputRequester: suspend (GuiInputRequest) -> Any,
) {
    /** The raw inventory slot that was clicked. */
    val slot: Int get() = event.rawSlot

    /**
     * Navigates the clicking player to another page of the same GUI.
     *
     * @param id the page id to open.
     */
    suspend fun openPage(id: String): Unit = pageOpener(id)

    /** Closes the GUI for the clicking player. */
    suspend fun close(): Unit = gui.close(player)

    /**
     * Closes the inventory and prompts the player to type a chat message,
     * suspending until they respond, cancel, or the request times out.
     *
     * @param prompt optional message sent to the player before waiting.
     * @return the plain-text message the player sent.
     * @throws GuiInputCancelledException if the input is cancelled (e.g. the
     *  player disconnects or another input request replaces this one).
     * @throws GuiInputTimeoutException if the player does not respond in time.
     */
    suspend fun chatInput(prompt: Component? = null): String =
        inputRequester(GuiInputRequest.Chat(prompt)) as String

    /**
     * Opens a virtual anvil and waits for the player to type a name and
     * take the result, suspending until they do, cancel, or time out.
     *
     * @param initialValue text pre-filled in the anvil's rename field.
     * @param title title of the anvil inventory.
     * @return the text the player entered.
     * @throws GuiInputCancelledException if the input is cancelled.
     * @throws GuiInputTimeoutException if the player does not respond in time.
     */
    suspend fun anvilInput(
        initialValue: String = "",
        title: Component = Component.text("Input"),
    ): String = inputRequester(GuiInputRequest.Anvil(initialValue, title)) as String

    /**
     * Opens a virtual sign and waits for the player to submit its lines,
     * suspending until they do, cancel, or time out.
     *
     * @param lines initial text shown on each of the sign's (up to 4) lines.
     * @return the plain-text lines the player submitted.
     * @throws GuiInputCancelledException if the input is cancelled.
     * @throws GuiInputTimeoutException if the player does not respond in time.
     */
    suspend fun signInput(lines: List<Component> = emptyList()): List<String> {
        @Suppress("UNCHECKED_CAST")
        return inputRequester(GuiInputRequest.Sign(lines)) as List<String>
    }
}

/**
 * Context passed to a [GuiCloseHandler] after a GUI's inventory is closed.
 *
 * @property player the player who closed the GUI.
 * @property gui the GUI definition that was being viewed.
 * @property lastPageId the id of the page that was open when it closed.
 */
data class GuiCloseContext(
    val player: Player,
    val gui: GuiDefinition,
    val lastPageId: String,
)

/**
 * Context passed to item and title renderers while a page is being drawn
 * for a player.
 *
 * @property player the player the page is being rendered for.
 * @property gui the GUI definition being rendered.
 * @property pageId the id of the page being rendered.
 */
data class GuiRenderContext(
    val player: Player,
    val gui: GuiDefinition,
    val pageId: String,
)
