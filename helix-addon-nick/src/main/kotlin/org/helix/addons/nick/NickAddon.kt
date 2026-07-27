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
 * Provides the in-game `/nick <name|off>` command. A nick is a full
 * disguise: the display profile it resolves is EXCLUSIVE, so the group
 * prefix and the clan tag of the real identity never leak — a nicked
 * player appears as a default player (configurable disguise prefix via
 * `nick.disguise`) under the assumed name in chat, tab list and the name
 * tag. Nicks persist across restarts and are protected against
 * impersonation: no account name or nick that is already taken can be
 * chosen.
 */
class NickAddon : AddonBase() {
    private val json = Json { prettyPrint = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private lateinit var msg: Messages

    /** Lowercase account name to nick. */
    private var nicks: Map<String, String> = emptyMap()

    /** Disguise settings (prefix shown instead of the real group prefix). */
    private var config: NickConfig = NickConfig()

    /**
     * Registers the `/nick` command, the display resolver and admin actions.
     */
    override fun enable() {
        nicks = load()
        config = loadConfig()
        msg = context.localizedMessages(messages())
        context.registerDisplayResolver { name ->
            nicks[name.lowercase()]?.let {
                DisplayProfile(prefix = config.prefix, name = it, color = config.color, exclusive = true)
            }
        }
        // Bridge value per player so the paper bridges notice a nick change within one poll
        // instead of waiting for the slow display-refresh cycle.
        nicks.keys.forEach(::publishNick)
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
                publishNick(player)
                ActionResult.ok("nick of $player removed")
            }
        }
        action(
            "nick.disguise",
            "Shows or sets the disguise prefix nicked players get instead of their group prefix.",
            "nick.disguise [prefix...] | nick.disguise clear",
        ) { invocation ->
            when {
                invocation.arguments.isEmpty() ->
                    ActionResult.ok("disguise prefix: '${config.prefix}'", "disguise color: '${config.color}'")
                invocation.arguments.first() == "clear" -> {
                    saveConfig(NickConfig())
                    ActionResult.ok("disguise prefix cleared — nicked players appear plain")
                }
                else -> {
                    // Trailing space separates the prefix from the name, mirroring the chat rules.
                    saveConfig(config.copy(prefix = invocation.arguments.joinToString(" ") + " "))
                    ActionResult.ok("disguise prefix set to '${config.prefix}'")
                }
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
            publishNick(executor)
            return ActionResult.ok(msg.formatFor(executor, "off.ok"))
        }
        if (!NICK_PATTERN.matches(argument)) {
            return ActionResult.error(msg.formatFor(executor, "error.format"))
        }
        if (taken(argument, executor)) {
            return ActionResult.error(msg.formatFor(executor, "error.taken"))
        }
        save(nicks + (executor.lowercase() to argument))
        publishNick(executor)
        return ActionResult.ok(msg.formatFor(executor, "set.ok", "nick" to argument))
    }

    /** Publishes the player's nick as the `nick.name.<name>` bridge value (empty = no nick). */
    private fun publishNick(player: String) {
        context.publishBridgeValue("nick.name.${player.lowercase()}", nicks[player.lowercase()] ?: "")
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

    private fun loadConfig(): NickConfig =
        context.storage().read("config")?.let { json.decodeFromString<NickConfig>(it) } ?: NickConfig()

    private fun saveConfig(updated: NickConfig) {
        config = updated
        context.storage().write("config", json.encodeToString(updated))
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

/**
 * Disguise settings for nicked players.
 *
 * @property prefix prefix shown instead of the real group prefix, for
 *   example `&7Spieler &f` — empty appears as a plain default player.
 * @property color name color code for the assumed name.
 */
@kotlinx.serialization.Serializable
data class NickConfig(
    val prefix: String = "",
    val color: String = "",
)
