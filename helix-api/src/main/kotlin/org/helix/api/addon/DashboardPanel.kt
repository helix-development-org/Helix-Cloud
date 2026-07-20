package org.helix.api.addon

import kotlinx.serialization.Serializable

/**
 * A dashboard page contributed by an addon.
 *
 * The dashboard shows [title] in the sidebar and renders [html] in a
 * sandboxed iframe. Inside that iframe the addon may use `<style>` and
 * `<script>` freely and call platform actions through the injected
 * `Helix.action(name, ...args)` bridge — the control token never leaves
 * the host page, so panel code cannot read it.
 *
 * @property id url-safe panel id, unique across addons, for example
 *   `permissions`.
 * @property title sidebar label, for example `Permissions`.
 * @property icon optional inner SVG markup (path/shape elements) for a
 *   24x24 `0 0 24 24` viewBox; empty falls back to a default icon.
 * @property html panel body markup; may contain `<style>` and `<script>`.
 */
@Serializable
data class DashboardPanel(
    val id: String,
    val title: String,
    val icon: String = "",
    val html: String,
) {
    init {
        require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "panel id must be url-safe: $id"
        }
        require(title.isNotBlank()) { "panel title must not be blank" }
    }
}
