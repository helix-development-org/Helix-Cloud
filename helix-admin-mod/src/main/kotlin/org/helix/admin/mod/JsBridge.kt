package org.helix.admin.mod

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import net.minecraft.client.Minecraft
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.handler.CefDisplayHandlerAdapter

/**
 * JavaScript bridge of the panel page over the console – independent of JCEF's message router, whose
 * renderer side does not reach MCEF's shared client reliably:
 *
 *  - page → mod: `console.log("helix-bridge:" + JSON.stringify({id, op, args}))`
 *  - mod → page: `window.__helixReply(id, ok, payloadJson)` (result data or the error message)
 *
 * Local ops are handled by the mod (`close` hides the panel, `interact` switches to mouse/typing mode, `hud` back to
 * the walkable HUD menu, `expired` drops a page without session so the next key press signs in again, `ping`
 * answers with the mod version and mode); everything else is forwarded as an in-game action to the server
 * ([AdminChannel.call]), which checks the Helix permission and executes it.
 */
object JsBridge {
    private val LOG = org.slf4j.LoggerFactory.getLogger("helix-admin")
    private const val PREFIX = "helix-bridge:"
    private var installedOn: CefClient? = null

    /** Installs the bridge once per CEF client. */
    fun install(client: CefClient) {
        if (installedOn === client) return
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(browser: CefBrowser?, level: CefSettings.LogSeverity?, message: String?, source: String?, line: Int): Boolean {
                if (browser == null || message == null || !message.startsWith(PREFIX)) return false
                try {
                    handle(browser, message.removePrefix(PREFIX))
                } catch (t: Throwable) {
                    LOG.error("panel bridge message failed", t)
                }
                return true
            }
        })
        installedOn = client
    }

    private fun handle(browser: CefBrowser, request: String) {
        val json = runCatching { JsonParser.parseString(request).asJsonObject }.getOrNull() ?: return
        val id = json.get("id")?.asLong ?: return
        val op = json.get("op")?.asString
        val reply = { ok: Boolean, payload: String -> reply(browser, id, ok, payload) }
        if (op == null) return reply(false, "invalid request")
        val args = json.getAsJsonObject("args") ?: JsonObject()
        val local = { action: () -> Unit ->
            Minecraft.getInstance().execute(action)
            reply(true, "{}")
        }
        when (op) {
            "close" -> local { PanelBrowser.close() }
            "interact" -> local { PanelBrowser.showMouse() }
            "hud" -> local { PanelBrowser.showHud() }
            "expired" -> local { PanelBrowser.markStale() }
            "ping" -> reply(true, JsonObject().apply {
                addProperty("mod", HelixAdminClient.VERSION)
                addProperty("mode", PanelBrowser.mode.name.lowercase())
            }.toString())
            else -> AdminChannel.call(op, args).whenComplete { result, error ->
                if (error != null) reply(false, error.cause?.message ?: error.message ?: "failed")
                else reply(true, (result.get("data") ?: JsonObject()).toString())
            }
        }
    }

    private fun reply(browser: CefBrowser, id: Long, ok: Boolean, payload: String) {
        val script = "window.__helixReply && window.__helixReply($id, $ok, ${JsonPrimitive(payload)})"
        Minecraft.getInstance().execute {
            runCatching { browser.executeJavaScript(script, browser.url, 0) }.onFailure { LOG.error("panel bridge reply failed", it) }
        }
    }
}
