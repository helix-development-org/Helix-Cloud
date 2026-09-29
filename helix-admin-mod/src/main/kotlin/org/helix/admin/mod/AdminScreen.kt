package org.helix.admin.mod

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Mouse/typing mode of the panel ([PanelBrowser.Mode.MOUSE]): mouse and keyboard go to the page – for input
 * fields and the full dashboard. No blur, no pause. Escape returns to the HUD menu; Right Shift stays a normal
 * key here (capital letters in input fields).
 */
class AdminScreen : Screen(Component.literal("Helix Admin")) {

    private val browser get() = PanelBrowser.browser

    override fun init() {
        PanelBrowser.fitToWindow()
        browser?.setFocus(true)
    }

    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // kein Abdunkeln/Weichzeichnen: die Welt bleibt hinter dem Panel sichtbar
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        PanelBrowser.draw(graphics, width, height, font)
        browser?.let { graphics.requestCursor(it.cursorType) }
    }

    override fun isPauseScreen(): Boolean = false

    private fun scaleX(x: Double): Int = (x * Minecraft.getInstance().window.width / width).toInt()
    private fun scaleY(y: Double): Int = (y * Minecraft.getInstance().window.height / height).toInt()

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        browser?.onMouseClicked(scaled(event), doubled)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        browser?.onMouseReleased(scaled(event))
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        browser?.onMouseScrolled(scaleX(mouseX), scaleY(mouseY), verticalAmount)
        return true
    }

    override fun mouseMoved(x: Double, y: Double) {
        browser?.onMouseMoved(scaleX(x), scaleY(y))
    }

    private fun scaled(event: MouseButtonEvent): MouseButtonEvent =
        MouseButtonEvent(scaleX(event.x()).toDouble(), scaleY(event.y()).toDouble(), event.buttonInfo())

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        browser?.onKeyPressed(event)
        return true
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        browser?.onKeyReleased(event)
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        browser?.onCharTyped(event)
        return true
    }

    override fun onClose() {
        super.onClose()
        PanelBrowser.onScreenClosed()
    }

    override fun removed() {
        browser?.setFocus(false)
    }
}
