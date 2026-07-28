package de.tytoss.igui.gui

import net.kyori.adventure.text.Component


internal sealed interface GuiInputRequest {
    /** A [de.tytoss.igui.gui.GuiClickContext.chatInput] request. */
    data class Chat(val prompt: Component?) : GuiInputRequest

    /** A [de.tytoss.igui.gui.GuiClickContext.anvilInput] request. */
    data class Anvil(val initialValue: String, val title: Component) : GuiInputRequest

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
