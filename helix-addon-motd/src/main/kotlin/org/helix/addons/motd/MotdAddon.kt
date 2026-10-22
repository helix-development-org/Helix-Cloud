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
 * counts, version text, the hover sample and the server icon (default: the
 * KronlandMC crown seal bundled as `/favicon.png`). While network maintenance is on,
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
            "Sets a MOTD field. Fields: line1, line2, version, hover (\\n separates lines), online, max (-1 = real), " +
                "favicon (Base64 of a 64x64 PNG, or 'default' for the network icon).",
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
        action(
            "motd.import",
            "Replaces the whole MOTD configuration (profiles, frames, interval) from JSON.",
            "motd.import <json>",
        ) { invocation ->
            val raw = invocation.arguments.joinToString(" ")
            val imported = runCatching { json.decodeFromString<MotdConfig>(raw) }.getOrNull()
                ?: return@action ActionResult.error("invalid motd JSON")
            if (imported.normal.frames.size > MAX_FRAMES || imported.maintenance.frames.size > MAX_FRAMES) {
                return@action ActionResult.error("too many frames (max $MAX_FRAMES)")
            }
            listOf(imported.normal, imported.maintenance)
                .map { MotdFavicon.normalize(it.favicon) }
                .filter { it.isNotEmpty() }
                .firstNotNullOfOrNull { MotdFavicon.validate(it) }
                ?.let { return@action ActionResult.error(it) }
            config = MotdConfig(
                normal = sanitize(imported.normal),
                maintenance = sanitize(imported.maintenance),
            )
            save()
            publish()
            ActionResult.ok(
                "motd updated (normal: ${config.normal.effectiveFrames().size} frames, " +
                    "maintenance: ${config.maintenance.effectiveFrames().size} frames)",
            )
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
            "favicon" -> {
                val icon = if (value.equals("default", ignoreCase = true)) "" else MotdFavicon.normalize(value)
                if (icon.isNotEmpty()) MotdFavicon.validate(icon)?.let { return ActionResult.error(it) }
                profile.copy(favicon = icon)
            }
            else -> return ActionResult.error("unknown field: $field (line1, line2, version, hover, online, max, favicon)")
        }
        config = config.with(profileName, updated)
        save()
        publish()
        return ActionResult.ok("motd $profileName.$field updated")
    }

    private fun describe(profile: MotdProfile): String =
        "'${profile.line1}' / '${profile.line2}' (online=${profile.onlinePlayers}, max=${profile.maxPlayers}, " +
            "frames=${profile.effectiveFrames().size} @ ${profile.frameIntervalMs}ms)"

    /**
     * Clamps the interval and keeps the base lines in sync with frame 0.
     *
     * @param profile the imported profile.
     * @return the sanitized profile.
     */
    private fun sanitize(profile: MotdProfile): MotdProfile {
        val first = profile.frames.firstOrNull()
        return profile.copy(
            frameIntervalMs = profile.frameIntervalMs.coerceAtLeast(MIN_INTERVAL_MS),
            line1 = first?.line1 ?: profile.line1,
            line2 = first?.line2 ?: profile.line2,
            favicon = MotdFavicon.normalize(profile.favicon),
        )
    }

    /**
     * Publishes the configuration with every empty favicon resolved to the network icon, so the
     * proxy always has an icon to serve while the stored configuration keeps "default" as empty.
     */
    private fun publish() {
        val effective = config.copy(
            normal = config.normal.copy(favicon = MotdFavicon.effective(config.normal)),
            maintenance = config.maintenance.copy(favicon = MotdFavicon.effective(config.maintenance)),
        )
        context.publishBridgeValue("motd.config", json.encodeToString(effective))
    }

    private fun load(): MotdConfig =
        context.storage().read("motd")?.let { json.decodeFromString(it) } ?: MotdConfig()

    private fun save() {
        context.storage().write("motd", json.encodeToString(config))
    }

    private companion object {
        /** Maximum animation frames per profile. */
        const val MAX_FRAMES = 20

        /** Minimum animation interval between server-list frames. */
        const val MIN_INTERVAL_MS = 500L
    }
}
