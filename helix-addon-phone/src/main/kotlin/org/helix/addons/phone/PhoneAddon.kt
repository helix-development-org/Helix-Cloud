package org.helix.addons.phone

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionResult
import org.helix.api.addon.PlayerListener
import org.helix.api.player.OnlinePlayer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Phone addon — the node-side source of truth for the in-game phone.
 *
 * Owns the app registry (edited from the dashboard), the uploaded app icons
 * (injected into the network pack at runtime) and the phone epoch. A player
 * is stamped with the current epoch when they join; the paper component asks
 * `phone.apps <player>` for the apps that player may see — filtered by
 * permission and by epoch, so a newly added app (and its freshly baked icon)
 * only appears for players who joined afterwards.
 */
class PhoneAddon : AddonBase() {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val compact = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private lateinit var config: PhoneConfig
    private lateinit var icons: PhoneIconStore
    private val playerEpoch = ConcurrentHashMap<String, Int>()

    @Volatile private var epoch: Int = 0

    /**
     * Loads the config, icons and epoch, registers the `phone.*` actions,
     * the join listener and the dashboard panel.
     */
    override fun enable() {
        loadMessages()
        config = loadConfig()
        epoch = loadEpoch()
        icons = PhoneIconStore(context.storage(), context)

        action("phone.get", "Exports the phone configuration as JSON (dashboard).", "phone.get") {
            ActionResult.ok(json.encodeToString(PhoneConfig.serializer(), config))
        }
        action("phone.set", "Replaces the phone configuration from JSON.", "phone.set <json>") { invocation ->
            setConfig(invocation.arguments.joinToString(" "))
        }
        action("phone.icons", "Lists the uploaded icon ids.", "phone.icons") {
            ActionResult.ok(compact.encodeToString(ListSerializer(String.serializer()), icons.ids().sorted()))
        }
        action(
            "phone.icon.put",
            "Stores an uploaded app icon PNG (base64) and rebuilds the pack.",
            "phone.icon.put <iconId> <base64png>",
        ) { invocation -> putIcon(invocation.arguments) }
        action("phone.icon.remove", "Removes an uploaded app icon.", "phone.icon.remove <iconId>") { invocation ->
            val iconId = invocation.arguments.firstOrNull() ?: return@action ActionResult.error("usage: phone.icon.remove <iconId>")
            icons.remove(iconId)
            ActionResult.ok("icon '$iconId' removed")
        }
        action(
            "phone.apps",
            "Lists the apps visible to a player (paper component).",
            "phone.apps <player>",
            bridgeInvocable = true,
        ) { invocation -> appsFor(invocation.arguments.firstOrNull().orEmpty()) }
        action(
            "phone.servers",
            "Lists the joinable backends for the navigator app (paper component).",
            "phone.servers",
            bridgeInvocable = true,
        ) { servers() }

        context.registerPlayerListener(object : PlayerListener {
            override fun onJoin(player: OnlinePlayer) {
                playerEpoch[player.name] = epoch
            }

            override fun onLeave(player: OnlinePlayer) {
                playerEpoch.remove(player.name)
            }
        })

        panel(
            "phone",
            "Phone",
            "/panel.html",
            "<rect x=\"7\" y=\"2\" width=\"10\" height=\"20\" rx=\"2\"/><line x1=\"11\" y1=\"18\" x2=\"13\" y2=\"18\"/>",
        )
    }

    /**
     * Validates and stores a replacement configuration, assigning a fresh
     * epoch to newly added apps so they only reach future joiners.
     *
     * @param raw the configuration JSON.
     * @return an ok result, or an error when the JSON is invalid.
     */
    private fun setConfig(raw: String): ActionResult {
        val incoming = runCatching { json.decodeFromString(PhoneConfig.serializer(), raw) }.getOrNull()
            ?: return ActionResult.error("invalid phone JSON")
        val previousIds = config.apps.map { it.id }.toSet()
        val sanitized = incoming.apps
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        val hasNew = sanitized.any { it.id !in previousIds }
        val nextEpoch = if (hasNew) epoch + 1 else epoch
        val apps = sanitized.map { app ->
            if (app.id !in previousIds) app.copy(sinceEpoch = nextEpoch) else app
        }
        config = PhoneConfig(apps)
        if (hasNew) {
            epoch = nextEpoch
            saveEpoch()
        }
        saveConfig()
        return ActionResult.ok("phone updated (${apps.size} app(s), epoch $epoch)")
    }

    /**
     * Decodes and stores an uploaded icon.
     *
     * @param arguments `[iconId, base64png]`.
     * @return an ok result, or an error when input is missing or not a PNG.
     */
    private fun putIcon(arguments: List<String>): ActionResult {
        val iconId = arguments.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: return ActionResult.error("usage: phone.icon.put <iconId> <base64png>")
        val raw = arguments.getOrNull(1)?.substringAfterLast(',').orEmpty()
        val bytes = runCatching { Base64.getDecoder().decode(raw) }.getOrNull()
            ?: return ActionResult.error("invalid base64")
        if (bytes.size > MAX_ICON_BYTES) {
            return ActionResult.error("icon too large (max ${MAX_ICON_BYTES / 1024} KB)")
        }
        return if (icons.put(iconId, bytes)) {
            ActionResult.ok("icon '$iconId' stored (generation ${context.networkPackGeneration()})")
        } else {
            ActionResult.error("not a PNG image")
        }
    }

    /**
     * Builds the app list visible to a player, filtered by permission and
     * epoch and enriched with each app's resolved icon glyph.
     *
     * @param player the player name.
     * @return an ok result whose first line is the JSON [AppView] list.
     */
    private fun appsFor(player: String): ActionResult {
        val visibleEpoch = playerEpoch[player] ?: epoch
        val views = config.apps
            .filter { it.enabled && it.sinceEpoch <= visibleEpoch }
            .filter { !it.adminOnly || context.hasPermission(player, ADMIN_PERMISSION) }
            .filter { it.permission.isBlank() || context.hasPermission(player, it.permission) }
            .sortedBy { it.order }
            .map { app ->
                val (font, char) = icons.resolve(app.icon)
                AppView(app.id, app.name, app.kind, app.command, app.screen, app.order, font, char)
            }
        return ActionResult.ok(compact.encodeToString(ListSerializer(AppView.serializer()), views))
    }

    /**
     * Builds the navigator server list from the node's live services: every
     * running backend.
     *
     * @return an ok result whose first line is the JSON [ServerEntry] list.
     */
    private fun servers(): ActionResult {
        val listing = context.actions.invoke(org.helix.api.action.ActionInvocation("service.list"))
        val entries = listing.lines.mapNotNull(::parseServiceLine).sortedWith(compareBy({ it.task }, { it.id }))
        return ActionResult.ok(compact.encodeToString(ListSerializer(ServerEntry.serializer()), entries))
    }

    /**
     * Parses one `service.list` line into a running-backend entry.
     *
     * @param line one output line (`<id> [<STATE>] port=<p> players=<n>/<max> …`).
     * @return the entry, or `null` when the line is not a running service.
     */
    private fun parseServiceLine(line: String): ServerEntry? {
        val id = line.substringBefore(' ').takeIf { it.isNotBlank() && it.contains('-') } ?: return null
        if (line.substringAfter('[', "").substringBefore(']', "") != "RUNNING") return null
        val players = line.substringAfter("players=", "").substringBefore('/', "")
        val max = line.substringAfter("players=", "").substringAfter('/', "").substringBefore(' ')
        return ServerEntry(id, id.substringBeforeLast('-'), players.toIntOrNull() ?: 0, max.toIntOrNull() ?: 0)
    }

    private fun loadConfig(): PhoneConfig =
        context.storage().read("phone")?.let {
            runCatching { compact.decodeFromString(PhoneConfig.serializer(), it) }.getOrNull()
        } ?: PhoneConfig()

    private fun saveConfig() {
        context.storage().write("phone", compact.encodeToString(PhoneConfig.serializer(), config))
    }

    private fun loadEpoch(): Int =
        context.storage().read("phone.epoch")?.trim()?.toIntOrNull() ?: 0

    private fun saveEpoch() {
        context.storage().write("phone.epoch", epoch.toString())
    }

    private companion object {
        /** Permission that reveals admin-only apps. */
        const val ADMIN_PERMISSION = "helix.phone.admin"

        /** Maximum accepted icon size in bytes. */
        const val MAX_ICON_BYTES = 256 * 1024
    }
}
