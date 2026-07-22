package org.helix.addons.motd

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionResult

/**
 * Server-list MOTD addon.
 *
 * Keeps two fully configurable profiles — `normal` and `maintenance` — and
 * publishes them as the `motd.config` bridge value. The velocity bridge
 * renders the matching profile on every server-list ping: lines (MiniMessage
 * and `&` codes, `{online}`/`{max}`/`{network}` placeholders), shown player
 * counts, version text and the hover sample. While network maintenance is on,
 * the maintenance profile is served automatically.
 */
class MotdAddon : AddonBase() {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private lateinit var config: MotdConfig

    /**
     * Publishes the configuration and registers the `motd.*` actions.
     */
    override fun enable() {
        config = load()
        publish()
        action(
            "motd.set",
            "Sets a MOTD field. Fields: line1, line2, version, hover (\\n separates lines), online, max (-1 = real).",
            "motd.set <normal|maintenance> <field> <value...>",
        ) { invocation -> set(invocation.arguments) }
        action("motd.show", "Shows both MOTD profiles.", "motd.show") {
            ActionResult.ok(
                "normal: ${describe(config.normal)}",
                "maintenance: ${describe(config.maintenance)}",
            )
        }
        action("motd.export", "Exports the MOTD configuration as JSON (dashboard).", "motd.export") {
            ActionResult.ok(json.encodeToString(config))
        }
        panel(
            "motd",
            "MOTD",
            "/panel.html",
            "<path d=\"M4 5h16v10H8l-4 4z\"/><path d=\"M8 9h8M8 12h5\"/>",
        )
    }

    private fun set(arguments: List<String>): ActionResult {
        val profileName = arguments.getOrNull(0)?.lowercase()
            ?: return ActionResult.error("usage: motd.set <normal|maintenance> <field> <value...>")
        val field = arguments.getOrNull(1)?.lowercase()
            ?: return ActionResult.error("usage: motd.set <normal|maintenance> <field> <value...>")
        val value = arguments.drop(2).joinToString(" ")
        val profile = config.profile(profileName)
            ?: return ActionResult.error("unknown profile: $profileName (use normal or maintenance)")
        val updated = when (field) {
            "line1" -> profile.copy(line1 = value)
            "line2" -> profile.copy(line2 = value)
            "version" -> profile.copy(versionText = value)
            "hover" -> profile.copy(hover = value.split("\\n").map { it.trim() }.filter { it.isNotEmpty() })
            "online" -> profile.copy(
                onlinePlayers = value.toIntOrNull()
                    ?: if (value.equals("real", ignoreCase = true)) -1 else return ActionResult.error("online must be a number or 'real'"),
            )
            "max" -> profile.copy(
                maxPlayers = value.toIntOrNull()
                    ?: if (value.equals("real", ignoreCase = true)) -1 else return ActionResult.error("max must be a number or 'real'"),
            )
            else -> return ActionResult.error("unknown field: $field (line1, line2, version, hover, online, max)")
        }
        config = config.with(profileName, updated)
        save()
        publish()
        return ActionResult.ok("motd $profileName.$field updated")
    }

    private fun describe(profile: MotdProfile): String =
        "'${profile.line1}' / '${profile.line2}' (online=${profile.onlinePlayers}, max=${profile.maxPlayers})"

    private fun publish() {
        context.publishBridgeValue("motd.config", json.encodeToString(config))
    }

    private fun load(): MotdConfig =
        context.storage().read("motd")?.let { json.decodeFromString(it) } ?: MotdConfig()

    private fun save() {
        context.storage().write("motd", json.encodeToString(config))
    }
}
