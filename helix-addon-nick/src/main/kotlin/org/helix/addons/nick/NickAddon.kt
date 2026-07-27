package org.helix.addons.nick

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.addon.sdk.AddonBase
import org.helix.api.display.DisplayProfile
import org.helix.api.message.Messages

/**
 * Nick addon.
 *
 * Provides the in-game `/nick <name|off>` command. A nick replaces the
 * NAME component of the player's display name (prefix stays with the
 * permission groups, suffix with the clans) and therefore shows in chat,
 * tab list and the name tag through the display-resolver pipeline. Nicks
 * persist across restarts and are protected against impersonation: no
 * account name or nick that is already taken can be chosen.
 */
class NickAddon : AddonBase() {
    private val json = Json { prettyPrint = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private lateinit var msg: Messages

    /** Lowercase account name to nick. */
    private var nicks: Map<String, String> = emptyMap()

    /**
     * Registers the `/nick` command, the display resolver and admin actions.
     */
    override fun enable() {
        nicks = load()
        msg = context.localizedMessages(messages())
        context.registerDisplayResolver { name ->
            nicks[name.lowercase()]?.let { DisplayProfile(name = it) }
        }
        action(
            name = "nick",
            description = "Changes your display name. '/nick off' restores the real name.",
            usage = "nick <name|off>",
            playerCommand = true,
            permission = "helix.nick",
            handler = ::handleNickCommand,
        )
        action("nick.list", "Lists all active nicks.", "nick.list") {
            if (nicks.isEmpty()) {
                ActionResult.ok("no active nicks")
            } else {
                ActionResult.ok(*nicks.map { (player, nick) -> "$player → $nick" }.toTypedArray())
            }
        }
        action("nick.clear", "Removes a player's nick (admin).", "nick.clear <player>") { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: nick.clear <player>")
            if (nicks[player.lowercase()] == null) {
                ActionResult.error("no nick for $player")
            } else {
                save(nicks - player.lowercase())
                ActionResult.ok("nick of $player removed")
            }
        }
    }

    private fun handleNickCommand(invocation: ActionInvocation): ActionResult {
        val executor = invocation.arguments.firstOrNull()
            ?: return ActionResult.error("missing executing player")
        val argument = invocation.arguments.getOrNull(1)
            ?: return ActionResult.error(msg.formatFor(executor, "usage"))
        if (argument.equals("off", ignoreCase = true)) {
            if (nicks[executor.lowercase()] == null) {
                return ActionResult.error(msg.formatFor(executor, "off.none"))
            }
            save(nicks - executor.lowercase())
            return ActionResult.ok(msg.formatFor(executor, "off.ok"))
        }
        if (!NICK_PATTERN.matches(argument)) {
            return ActionResult.error(msg.formatFor(executor, "error.format"))
        }
        if (taken(argument, executor)) {
            return ActionResult.error(msg.formatFor(executor, "error.taken"))
        }
        save(nicks + (executor.lowercase() to argument))
        return ActionResult.ok(msg.formatFor(executor, "set.ok", "nick" to argument))
    }

    /**
     * Whether a nick would impersonate someone: it matches an online
     * player's account name or another player's active nick. The player's
     * own account name stays allowed (cosmetic re-casing).
     */
    private fun taken(nick: String, executor: String): Boolean {
        val lower = nick.lowercase()
        if (lower == executor.lowercase()) return false
        if (context.onlinePlayers().any { it.name.lowercase() == lower }) return true
        return nicks.any { (owner, active) -> owner != executor.lowercase() && active.lowercase() == lower }
    }

    private fun load(): Map<String, String> =
        context.storage().read("nicks")?.let { json.decodeFromString(mapSerializer, it) } ?: emptyMap()

    private fun save(updated: Map<String, String>) {
        nicks = updated
        context.storage().write("nicks", json.encodeToString(mapSerializer, updated))
    }

    private fun messages(): Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            "usage" to "&cUsage: /nick \\<name> &7or &c/nick off",
            "set.ok" to "&aYou are now displayed as &f{nick}&a.",
            "off.ok" to "&7Your real name is shown again.",
            "off.none" to "&cYou have no nick set.",
            "error.format" to "&cNicks are 3-16 characters: letters, digits and underscores.",
            "error.taken" to "&cThat name is already in use.",
        ),
        "de" to mapOf(
            "usage" to "&cBenutzung: /nick \\<name> &7oder &c/nick off",
            "set.ok" to "&aDu wirst jetzt als &f{nick} &aangezeigt.",
            "off.ok" to "&7Dein echter Name wird wieder angezeigt.",
            "off.none" to "&cDu hast keinen Nick gesetzt.",
            "error.format" to "&cNicks haben 3-16 Zeichen: Buchstaben, Ziffern und Unterstriche.",
            "error.taken" to "&cDieser Name ist bereits vergeben.",
        ),
    )

    private companion object {
        /** Allowed nick shape, mirroring Minecraft account names. */
        val NICK_PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")
    }
}
