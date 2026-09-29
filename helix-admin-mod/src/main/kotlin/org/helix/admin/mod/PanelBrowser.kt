package org.helix.admin.mod

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.dimaskama.mcef.api.MCEFApi
import net.dimaskama.mcef.api.MCEFBrowser
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.render.state.BlitRenderState
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.joml.Matrix3x2f

/**
 * The embedded panel browser and how it is shown:
 *
 *  - [Mode.HUD] – drawn as a HUD layer; the player keeps walking and looking around, the quick menu is steered with
 *    the navigation keys ([nav] → `helix-nav` events on the page)
 *  - [Mode.MOUSE] – [AdminScreen] on top: mouse and typing go to the page (input fields, full dashboard)
 *  - [Mode.CLOSED] – hidden; the browser survives for a fast reopen and is released on disconnect
 *
 * Every mode change is announced to the page as a `helix-mode` event.
 */
object PanelBrowser {
    /** How the panel is shown. */
    enum class Mode { CLOSED, HUD, MOUSE }

    private val LOG = org.slf4j.LoggerFactory.getLogger("helix-admin")

    /** Current mode. */
    var mode: Mode = Mode.CLOSED
        private set

    internal var browser: MCEFBrowser? = null
        private set
    private var loadedUrl: String? = null
    /** URL waiting for the new browser to exist (it starts on about:blank so the JS bridge is there first). */
    @Volatile private var pendingUrl: String? = null
    /** The page lost its session – the next key press fetches a fresh ticket. */
    @Volatile private var stale = false
    @Volatile private var loadError: String? = null
    @Volatile private var loadedOnce = false
    private var sizeW = 0
    private var sizeH = 0

    /** Whether a usable page is there (reopen without a new ticket). */
    val ready: Boolean get() = browser != null && loadError == null && !stale

    /** Loads [url] (a fresh ticket URL) – a new browser starts directly with it. */
    fun load(url: String) {
        stale = false
        val existing = browser
        if (loadedUrl != url) {
            loadError = null
            loadedOnce = false
            loadedUrl = url
        }
        if (existing == null) {
            // Erst about:blank: der native Browser entsteht asynchron (loadURL() direkt danach ginge verloren),
            // und die JS-Brücke muss angemeldet sein, bevor das Panel lädt – sonst fehlt window.cefQuery.
            // Die Panel-URL folgt, sobald about:blank fertig ist (onLoadingStateChange).
            pendingUrl = url
            MCEFApi.getInstance().createBrowser("about:blank", true).also {
                browser = it
                JsBridge.install(it.cefBrowser.client)
                installLoadHandler(it)
            }
        } else {
            existing.cefBrowser.loadURL(url)
        }
        fitToWindow()
    }

    /** The page has no session any more (e.g. expired): hide it; the next key press gets a fresh ticket. */
    fun markStale() {
        stale = true
        loadedUrl = null
        close()
    }

    /** Shows the panel as HUD layer (walking stays possible). */
    fun showHud() {
        if (browser == null) return
        mode = Mode.HUD
        val mc = Minecraft.getInstance()
        if (mc.screen is AdminScreen) mc.setScreen(null)
        browser?.setFocus(false)
        announce("hud")
    }

    /** Switches to mouse/typing mode ([AdminScreen]). */
    fun showMouse() {
        if (browser == null) return
        mode = Mode.MOUSE
        val mc = Minecraft.getInstance()
        if (mc.screen !is AdminScreen) mc.setScreen(AdminScreen())
        announce("mouse")
    }

    /** Hides the panel. */
    fun close() {
        if (mode == Mode.CLOSED) return
        mode = Mode.CLOSED
        val mc = Minecraft.getInstance()
        if (mc.screen is AdminScreen) mc.setScreen(null)
        browser?.setFocus(false)
        announce("closed")
    }

    /** [AdminScreen] was closed (Escape): back to the HUD menu. */
    internal fun onScreenClosed() {
        if (mode == Mode.MOUSE) showHud()
    }

    /** Sends a navigation key (`up`, `down`, `left`, `right`, `select`, `back`) to the page. */
    fun nav(key: String) = dispatch("helix-nav", key)

    private fun announce(mode: String) = dispatch("helix-mode", mode)

    private fun dispatch(event: String, detail: String) {
        val b = browser ?: return
        guard("dispatch $event") {
            b.cefBrowser.executeJavaScript("window.dispatchEvent(new CustomEvent('$event', {detail: '$detail'}))", b.cefBrowser.url, 0)
        }
    }

    /** Browser in physical pixels (crisp text), displayed over the scaled GUI size. */
    fun fitToWindow() {
        val b = browser ?: return
        val window = Minecraft.getInstance().window
        if (window.width == sizeW && window.height == sizeH) return
        sizeW = window.width
        sizeH = window.height
        if (sizeW > 0 && sizeH > 0) b.resize(sizeW, sizeH)
    }

    /** Draws the page over [width]×[height] GUI units, or a loading/error hint while there is none. */
    fun draw(graphics: GuiGraphics, width: Int, height: Int, font: net.minecraft.client.gui.Font) {
        val b = browser ?: return
        fitToWindow()
        val error = loadError
        val view = b.textureView
        if (error != null || view == null || !loadedOnce) {
            val lines = if (error != null) {
                listOf(Component.translatable("helix-admin.page_failed"), Component.literal(error), Component.literal(loadedUrl.orEmpty().substringBefore('#')))
            } else {
                listOf(Component.translatable("helix-admin.page_loading"), Component.literal(loadedUrl.orEmpty().substringBefore('#')))
            }
            val boxW = lines.maxOf { font.width(it) } + 16
            graphics.fill(8, 8, 8 + boxW, 8 + 8 + lines.size * 12, 0xD0101018.toInt())
            lines.forEachIndexed { i, line -> graphics.drawString(font, line, 16, 14 + i * 12, if (i == 0) -0x1 else -0x555556) }
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
    }

    /** Releases the browser (disconnect / server switch to a network without the panel). */
    fun release() {
        close()
        browser?.close()
        browser = null
        loadedUrl = null
        pendingUrl = null
        stale = false
        loadError = null
        loadedOnce = false
        sizeW = 0
        sizeH = 0
    }

    private fun installLoadHandler(b: MCEFBrowser) {
        // Callbacks kommen nativ aus CEF: Parameter können null sein, Exceptions dürfen nicht hinaus
        b.cefBrowser.client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(browser: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
                guard("loading state") {
                    val current = browser?.url.orEmpty()
                    LOG.info("panel loading={} {}", isLoading, current.substringBefore('#'))
                    val pending = pendingUrl
                    if (!isLoading && pending != null && browser != null) {
                        pendingUrl = null
                        browser.loadURL(pending)
                    } else if (!isLoading && current.startsWith("http") && loadError == null) {
                        loadedOnce = true
                    }
                }
            }

            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                guard("load end") {
                    if (frame?.isMain != true) return@guard
                    LOG.info("panel page loaded: HTTP {} {}", httpStatusCode, frame.url.orEmpty().substringBefore('#'))
                    if (httpStatusCode >= 400) loadError = "HTTP $httpStatusCode"
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

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            LOG.error("panel browser '{}' failed", what, t)
        }
    }
}
