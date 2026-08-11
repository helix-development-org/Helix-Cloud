package de.tytoss.igui.gui

import net.kyori.adventure.text.Component

internal sealed interface GuiInputRequest {
    /** A [de.tytoss.igui.gui.GuiClickContext.chatInput] request. */
    data class Chat(val prompt: Component?) : GuiInputRequest

    /**
     * A [de.tytoss.igui.gui.GuiClickContext.anvilInput] request.
     *
     * @property preview optional per-keystroke renderer for the anvil result
     *  item's name: called with the current rename text on every
     *  `PrepareAnvilEvent`, so the player sees a live, formatted preview of
     *  what they are typing (e.g. rendered MiniMessage). `null` shows the raw
     *  typed text.
     */
    data class Anvil(
        val initialValue: String,
        val title: Component,
        val preview: ((String) -> Component)? = null,
    ) : GuiInputRequest

    /** A [de.tytoss.igui.gui.GuiClickContext.signInput] request. */
    data class Sign(val lines: List<Component>) : GuiInputRequest
}

/**
 * Thrown by the `*Input` suspend functions on [de.tytoss.igui.gui.GuiClickContext]
 * when the request is cancelled before the player responds — for example the
 * player disconnects, or a second input request replaces this one.
 */
class GuiInputCancelledException : IllegalStateException("GUI input was cancelled")

/**
 * Thrown by the `*Input` suspend functions on [de.tytoss.igui.gui.GuiClickContext]
 * when the player does not respond within the input timeout.
 */
class GuiInputTimeoutException : IllegalStateException("GUI input timed out")
