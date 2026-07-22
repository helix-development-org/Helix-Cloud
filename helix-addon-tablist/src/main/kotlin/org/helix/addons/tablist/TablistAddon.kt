package org.helix.addons.tablist

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionResult

/**
 * Tab list addon.
 *
 * Publishes header and footer as bridge values; the paper bridge applies
 * them to all players every few seconds and substitutes the `{online}`
 * and `{max}` placeholders.
 */
class TablistAddon : AddonBase() {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
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
            ActionResult.ok(
                "header: ${config.header}",
                "footer: ${config.footer}",
                "frames: ${config.effectiveHeaderFrames().size} @ ${config.intervalMs}ms",
            )
        }
        action(
            "tablist.import",
            "Replaces the whole tab list configuration (frames, interval) from JSON.",
            "tablist.import <json>",
        ) { invocation ->
            val raw = invocation.arguments.joinToString(" ")
            val imported = runCatching { json.decodeFromString<TablistConfig>(raw) }.getOrNull()
                ?: return@action ActionResult.error("invalid tablist JSON")
            if (imported.headerFrames.size > MAX_FRAMES || imported.footerFrames.size > MAX_FRAMES) {
                return@action ActionResult.error("too many frames (max $MAX_FRAMES)")
            }
            config = imported.copy(
                intervalMs = imported.intervalMs.coerceAtLeast(MIN_INTERVAL_MS),
                header = imported.headerFrames.firstOrNull() ?: imported.header,
                footer = imported.footerFrames.firstOrNull() ?: imported.footer,
            )
            save()
            publish()
            ActionResult.ok("tab list updated (${config.effectiveHeaderFrames().size} frames @ ${config.intervalMs}ms)")
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
        context.publishBridgeValue("tablist.config", json.encodeToString(config))
    }

    private fun load(): TablistConfig =
        context.storage().read("tablist")?.let { json.decodeFromString(it) } ?: TablistConfig()

    private fun save() {
        context.storage().write("tablist", json.encodeToString(config))
    }

    private companion object {
        /** Maximum animation frames per header/footer. */
        const val MAX_FRAMES = 20

        /** Minimum animation interval to avoid client spam. */
        const val MIN_INTERVAL_MS = 250L
    }
}
