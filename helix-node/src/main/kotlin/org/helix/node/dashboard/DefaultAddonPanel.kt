package org.helix.node.dashboard

import org.helix.api.action.ActionDescriptor
import org.helix.api.addon.AddonManifest
import org.helix.api.addon.DashboardPanel

/**
 * Builds the dashboard page an addon gets when it does not register one
 * itself, so EVERY enabled addon has a panel: manifest metadata plus the
 * live state known once its `onEnable` completed — the actions it
 * registered and the HXA components it shipped. An addon that registers
 * any own panel via `AddonContext.registerDashboardPanel` overrides this
 * default (it is simply never generated for that addon).
 */
object DefaultAddonPanel {

    /**
     * Generates the default page for one enabled addon.
     *
     * @param manifest the addon's manifest.
     * @param actions descriptors of the actions this addon registered.
     * @param components human-readable names of the HXA components it
     *  shipped, for example `paper.jar` or `pack.zip`.
     * @return the generated panel, id-safe for addon ids containing dots.
     */
    fun build(manifest: AddonManifest, actions: List<ActionDescriptor>, components: List<String>): DashboardPanel =
        DashboardPanel(
            id = panelId(manifest.id),
            title = manifest.name,
            html = html(manifest, actions, components),
        )

    /**
     * The url-safe panel id derived from an addon id (dots become dashes,
     * for example `helix.profile` → `addon-helix-profile`). Prefixed so a
     * generated page can never collide with a hand-registered panel id.
     *
     * @param addonId the addon id.
     * @return the derived panel id.
     */
    fun panelId(addonId: String): String = "addon-" + addonId.replace('.', '-')

    private fun html(manifest: AddonManifest, actions: List<ActionDescriptor>, components: List<String>): String {
        val meta = listOf(
            "Id" to manifest.id,
            "Version" to manifest.version,
            "Status" to "enabled",
            "Components" to components.joinToString(", ").ifEmpty { "node-only (addon.jar)" },
        ).joinToString("") { (label, value) ->
            "<tr><td style=\"color:var(--text-dim)\">${escape(label)}</td><td>${escape(value)}</td></tr>"
        }
        val actionRows = actions.sortedBy { it.name }.joinToString("") { action ->
            val badges = buildString {
                if (action.playerCommand) append(" <code>/${escape(action.name)}</code>")
                action.permission?.let { append(" <code>${escape(it)}</code>") }
            }
            "<tr><td><code>${escape(action.usage)}</code>$badges</td>" +
                "<td>${escape(action.description)}</td></tr>"
        }
        val actionsCard = if (actions.isEmpty()) "" else """
            <div class="card">
              <div class="card-head"><h2>Actions (${actions.size})</h2></div>
              <div class="card-body"><table><tbody>$actionRows</tbody></table></div>
            </div>
        """.trimIndent()
        return """
            <div class="card">
              <div class="card-head"><h2>${escape(manifest.name)}</h2></div>
              <div class="card-body">
                <p>${escape(manifest.description.ifBlank { "No description." })}</p>
                <table><tbody>$meta</tbody></table>
              </div>
            </div>
            $actionsCard
        """.trimIndent()
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
