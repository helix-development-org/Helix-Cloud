package org.helix.addons.admin.paper

import java.net.URI

/**
 * Builds the panel address the client mod opens. The node's control port is known, so nobody has to type it:
 *
 *  - nothing configured → the address the player joined with, on the control port
 *  - no scheme (`panel.example.com`) → `http://`
 *  - `http://` without a port → the control port is added (the dashboard is served there)
 *  - `https://` without a port stays as it is (reverse proxy on 443); an explicit port is always kept
 */
object PanelUrl {
    /**
     * Resolves the panel base URL (without trailing slash).
     *
     * @param configured the value of `admin.panelurl`, or null.
     * @param joinHost the host the player used to join, or null.
     * @param controlPort the node's control (HTTP) port.
     * @return the base URL.
     */
    fun resolve(configured: String?, joinHost: String?, controlPort: Int): String {
        val raw = configured?.trim()?.trimEnd('/')?.ifBlank { null }
            ?: return "http://${joinHost?.takeIf { it.isNotBlank() } ?: "127.0.0.1"}:$controlPort"
        val withScheme = if ("://" in raw) raw else "http://$raw"
        val uri = runCatching { URI.create(withScheme) }.getOrNull() ?: return withScheme
        if (uri.port != -1 || !uri.scheme.equals("http", ignoreCase = true) || uri.host == null) return withScheme
        val path = uri.rawPath.orEmpty()
        return "${uri.scheme}://${uri.host}:$controlPort$path"
    }
}
