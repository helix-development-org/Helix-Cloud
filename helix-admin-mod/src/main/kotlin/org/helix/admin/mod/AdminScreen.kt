package org.helix.admin.mod

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.dimaskama.mcef.api.MCEFApi
import net.dimaskama.mcef.api.MCEFBrowser
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.render.state.BlitRenderState
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import org.joml.Matrix3x2f
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.lwjgl.glfw.GLFW

/**
 * Overlay screen showing the panel page in an embedded, transparent Chromium browser on top of the running
 * game (no blur, no pause) – the quick menu sits at the side like txAdmin's, the full dashboard opens in the
 * same browser. Escape (or the panel's close button) closes it; Right Shift stays usable for typing. The browser survives closing (fast reopen) and is released
 * on disconnect.
 */
class AdminScreen private constructor() : Screen(Component.literal("Helix Admin")) {

    override fun init() {
        val b = browser ?: return
        // Browser in physical pixels, displayed over the scaled GUI size → crisp text
        val window = Minecraft.getInstance().window
        b.resize(window.width, window.height)
        b.setFocus(true)
    }

    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // kein Abdunkeln/Weichzeichnen: die Welt bleibt hinter dem Panel sichtbar
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val b = browser ?: return
        val error = loadError
        val view = b.textureView
        if (error != null || view == null || !loadedOnce) {
            // Seite noch nicht da (oder Fehler): sichtbarer Hinweis statt eines durchsichtigen, leeren Browsers
            graphics.fill(0, 0, width, height, 0xC0101018.toInt())
            val lines = if (error != null) {
                listOf(Component.translatable("helix-admin.page_failed"), Component.literal(error), Component.literal(loadedUrl.orEmpty().substringBefore('#')), Component.translatable("helix-admin.esc"))
            } else {
                listOf(Component.translatable("helix-admin.page_loading"), Component.literal(loadedUrl.orEmpty().substringBefore('#')), Component.translatable("helix-admin.esc"))
            }
            lines.forEachIndexed { i, line ->
                graphics.drawCenteredString(font, line, width / 2, height / 2 - 20 + i * 12, if (i == 0) -0x1 else -0x555556)
            }
            if (error != null || view == null) return
        }
        graphics.guiRenderState.submitGuiElement(
            BlitRenderState(
                RenderPipelines.GUI_TEXTURED,
                TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
                Matrix3x2f(graphics.pose()),
                0, 0, width, height,
                0f, 1f, 0f, 1f,
                -0x1,
                graphics.scissorStack.peek(),
            ),
        )
        graphics.requestCursor(b.cursorType)
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
        // Nur Escape schließt – Right Shift ist hier ein normales Zeichen (Großbuchstaben in Eingabefeldern)
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

    override fun removed() {
        browser?.setFocus(false)
    }

    companion object {
        private val LOG = org.slf4j.LoggerFactory.getLogger("helix-admin")
        private const val BRIDGE_CHECK =
            "if (typeof window.cefQuery !== 'function' && !sessionStorage.getItem('helix.bridgeReload')) {" +
                " sessionStorage.setItem('helix.bridgeReload', '1'); location.reload() }"
        private var browser: MCEFBrowser? = null
        private var loadedUrl: String? = null
        @Volatile private var loadError: String? = null
        @Volatile private var loadedOnce = false

        /** Opens the overlay with [url] (a fresh login ticket URL → reload; otherwise keep the page). */
        fun open(url: String) {
            val api = MCEFApi.getInstance()
            // Neuer Browser startet direkt mit der Ziel-URL: loadURL() direkt nach createBrowser() geht verloren,
            // weil der native Browser asynchron entsteht
            val existing = browser
            val b = existing ?: api.createBrowser(url, true).also {
                browser = it
                JsBridge.install(it.cefBrowser.client)
                // Callbacks kommen nativ aus CEF: Parameter können null sein, Exceptions dürfen nicht hinaus
                it.cefBrowser.client.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadingStateChange(browser: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
                        guard("loading state") {
                            val current = browser?.url.orEmpty()
                            LOG.info("panel loading={} {}", isLoading, current.substringBefore('#'))
                            if (!isLoading && current.startsWith("http") && loadError == null) loadedOnce = true
                        }
                    }

                    override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                        guard("load end") {
                            if (frame?.isMain != true) return@guard
                            LOG.info("panel page loaded: HTTP {} {}", httpStatusCode, frame.url.orEmpty().substringBefore('#'))
                            if (httpStatusCode >= 400) loadError = "HTTP $httpStatusCode"
                            // Die JS-Brücke wird erst nach dem Erzeugen des Browsers angemeldet; fehlt window.cefQuery
                            // auf der ersten Seite, einmal neu laden (Sitzung liegt im localStorage)
                            if (frame.url.orEmpty().startsWith("http")) frame.executeJavaScript(BRIDGE_CHECK, frame.url, 0)
                        }
                    }

                    override fun onLoadError(browser: CefBrowser?, frame: CefFrame?, errorCode: CefLoadHandler.ErrorCode?, errorText: String?, failedUrl: String?) {
                        guard("load error") {
                            if (frame?.isMain != true || errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return@guard
                            loadError = "$errorCode ${errorText.orEmpty()}".trim()
                            LOG.warn("panel page failed: {} {} ({})", errorCode, errorText, failedUrl.orEmpty().substringBefore('#'))
                        }
                    }
                })
            }
            if (loadedUrl != url) {
                loadError = null
                loadedOnce = false
                if (existing != null) b.cefBrowser.loadURL(url)
                loadedUrl = url
            }
            Minecraft.getInstance().setScreen(AdminScreen())
        }

        private inline fun guard(what: String, block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                LOG.error("panel browser callback '{}' failed", what, t)
            }
        }

        /** Reopens the overlay with the page as it was left, if a browser exists. */
        fun reopen(): Boolean {
            if (browser == null || loadError != null) return false
            Minecraft.getInstance().setScreen(AdminScreen())
            return true
        }

        fun closeIfOpen() {
            val mc = Minecraft.getInstance()
            if (mc.screen is AdminScreen) mc.setScreen(null)
        }

        /** Releases the browser (disconnect / server switch to a network without the panel). */
        fun release() {
            closeIfOpen()
            browser?.close()
            browser = null
            loadedUrl = null
            loadError = null
            loadedOnce = false
        }
    }
}
