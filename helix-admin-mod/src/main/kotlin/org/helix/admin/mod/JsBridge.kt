package org.helix.admin.mod

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter

/**
 * JavaScript bridge of the panel page: `window.cefQuery({request: JSON.stringify({op, args}), onSuccess, onFailure})`.
 *
 * Local ops are handled by the mod (`close` closes the overlay, `ping` answers with the mod version);
 * everything else is forwarded as an in-game action to the server ([AdminChannel.call]), which checks
 * the Helix permission and executes it. The page receives the server's JSON result.
 */
object JsBridge {
    private val LOG = org.slf4j.LoggerFactory.getLogger("helix-admin")
    private var installedOn: CefClient? = null

    /** Installs the bridge once per CEF client. */
    fun install(client: CefClient) {
        if (installedOn === client) return
        val router = CefMessageRouter.create()
        router.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(browser: CefBrowser?, frame: CefFrame?, queryId: Long, request: String?, persistent: Boolean, callback: CefQueryCallback?): Boolean {
                if (callback == null) return false
                try {
                    handle(request.orEmpty(), callback)
                } catch (t: Throwable) {
                    LOG.error("panel bridge query failed", t)
                    callback.failure(500, t.message ?: "failed")
                }
                return true
            }
        }, true)
        client.addMessageRouter(router)
        installedOn = client
    }

    private fun handle(request: String, callback: CefQueryCallback) {
        val json = runCatching { JsonParser.parseString(request).asJsonObject }.getOrNull()
        val op = json?.get("op")?.asString
        if (json == null || op == null) {
            callback.failure(400, "invalid request")
            return
        }
        val args = json.getAsJsonObject("args") ?: JsonObject()
        when (op) {
            "close" -> {
                Minecraft.getInstance().execute { AdminScreen.closeIfOpen() }
                callback.success("{}")
            }
            "ping" -> callback.success(JsonObject().apply { addProperty("mod", HelixAdminClient.VERSION) }.toString())
            else -> AdminChannel.call(op, args).whenComplete { result, error ->
                if (error != null) callback.failure(500, error.cause?.message ?: error.message ?: "failed")
                else callback.success((result.get("data") ?: JsonObject()).toString())
            }
        }
    }
}
