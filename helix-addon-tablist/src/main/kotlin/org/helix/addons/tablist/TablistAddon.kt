package org.helix.addons.tablist

import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionResult

/**
 * Persisted tab list configuration.
 *
 * @property header header text, `&` colors and `\n` line breaks.
 * @property footer footer text.
 */
@Serializable
data class TablistConfig(
    val header: String = "&6Helix-Cloud",
    val footer: String = "&7{online}&8/&7{max} players",
)

/**
 * Tab list addon.
 *
 * Publishes header and footer as bridge values; the paper bridge applies
 * them to all players every few seconds and substitutes the `{online}`
 * and `{max}` placeholders.
 */
class TablistAddon : AddonBase() {
    private val json = Json { prettyPrint = true }
    private lateinit var config: TablistConfig

    /**
     * Publishes the configuration and registers the tablist actions.
     */
    override fun enable() {
        config = load()
        publish()
        action(
            "tablist.header",
            "Sets the tab list header. Use \\n for line breaks.",
            "tablist.header <text...>",
        ) { invocation ->
            val text = invocation.arguments.joinToString(" ")
            if (text.isBlank()) {
                ActionResult.error("usage: tablist.header <text...>")
            } else {
                config = config.copy(header = text)
                save()
                publish()
                ActionResult.ok("tab list header updated")
            }
        }
        action(
            "tablist.footer",
            "Sets the tab list footer. Placeholders: {online}, {max}.",
            "tablist.footer <text...>",
        ) { invocation ->
            val text = invocation.arguments.joinToString(" ")
            if (text.isBlank()) {
                ActionResult.error("usage: tablist.footer <text...>")
            } else {
                config = config.copy(footer = text)
                save()
                publish()
                ActionResult.ok("tab list footer updated")
            }
        }
        action("tablist.show", "Shows the current tab list configuration.", "tablist.show") {
            ActionResult.ok("header: ${config.header}", "footer: ${config.footer}")
        }
        action("tablist.export", "Exports the tab list configuration as JSON (dashboard).", "tablist.export") {
            ActionResult.ok(json.encodeToString(config))
        }
        panel(
            "tablist",
            "Tablist",
            "/panel.html",
            "<rect x=\"3\" y=\"4\" width=\"18\" height=\"16\" rx=\"2\"/><path d=\"M3 9h18M8 13h8M8 16h5\"/>",
        )
    }

    private fun publish() {
        context.publishBridgeValue("tablist.header", config.header.replace("\\n", "\n"))
        context.publishBridgeValue("tablist.footer", config.footer.replace("\\n", "\n"))
    }

    private fun load(): TablistConfig {
        val file = context.dataDirectory.resolve("tablist.json")
        return if (Files.exists(file)) {
            json.decodeFromString(Files.readString(file))
        } else {
            TablistConfig()
        }
    }

    private fun save() {
        Files.writeString(context.dataDirectory.resolve("tablist.json"), json.encodeToString(config))
    }
}
