package org.helix.addons.profile

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.addon.ProfileSettingType
import org.helix.api.addon.ProfileView
import org.helix.api.addon.ResolvedSetting
import org.helix.api.message.Messages

/**
 * Profile addon.
 *
 * The single place a player's cross-addon profile lives: read-only info
 * ([org.helix.api.addon.ProfileInfoProvider]) and interactive settings
 * ([org.helix.api.addon.ProfileSettingProvider]) contributed by any other
 * addon, aggregated by the node without those addons knowing about each
 * other. This addon owns the actual chosen VALUE of every setting
 * ([ProfileStore]); contributing addons only describe what can be chosen.
 */
class ProfileAddon : AddonBase() {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private lateinit var store: ProfileStore
    private lateinit var msg: Messages

    /**
     * Registers the profile actions, the `/profile` player command and the
     * dashboard panel.
     */
    override fun enable() {
        store = ProfileStore(context.storage())
        msg = loadMessages()

        action(
            "profile.view",
            "Builds a player's full profile view (info + settings + current values).",
            "profile.view <player>",
            bridgeInvocable = true,
        ) { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: profile.view <player>")
            ActionResult.ok(json.encodeToString(buildView(player)))
        }

        action(
            "profile.setting.get",
            "Reads a player's current value for one setting (falls back to the descriptor's default).",
            "profile.setting.get <player> <owner> <key>",
        ) { invocation ->
            val (player, owner, key) = invocation.arguments.let {
                Triple(it.getOrNull(0), it.getOrNull(1), it.getOrNull(2))
            }
            if (player == null || owner == null || key == null) {
                return@action ActionResult.error("usage: profile.setting.get <player> <owner> <key>")
            }
            ActionResult.ok(store.get(player, owner, key) ?: defaultFor(player, owner, key))
        }

        action(
            "profile.setting.set",
            "Sets a player's own chosen value for a setting (enforces per-option gating).",
            "profile.setting.set <player> <owner> <key> <value>",
            bridgeInvocable = true,
        ) { invocation -> setSetting(invocation, asAdmin = false) }

        action(
            "profile.setting.admin-set",
            "Staff override: sets any player's setting, bypassing per-option gating.",
            "profile.setting.admin-set <player> <owner> <key> <value>",
        ) { invocation -> setSetting(invocation, asAdmin = true) }

        action(
            "profile.setting.clear",
            "Resets a player's setting back to its default.",
            "profile.setting.clear <player> <owner> <key>",
        ) { invocation ->
            val (player, owner, key) = invocation.arguments.let {
                Triple(it.getOrNull(0), it.getOrNull(1), it.getOrNull(2))
            }
            if (player == null || owner == null || key == null) {
                return@action ActionResult.error("usage: profile.setting.clear <player> <owner> <key>")
            }
            store.clear(player, owner, key)
            context.notifyProfileSettingChanged(owner, player, key, defaultFor(player, owner, key))
            ActionResult.ok("cleared")
        }

        action(
            "profile",
            "Shows or changes your own profile.",
            "profile <set <key> <value>>",
            playerCommand = true,
        ) { invocation -> profileCommand(invocation) }

        panel(
            "profile",
            "Profiles",
            "/panel.html",
            "<circle cx=\"12\" cy=\"8\" r=\"4\"/><path d=\"M4 20c0-4.4 3.6-8 8-8s8 3.6 8 8\"/>",
        )
    }

    /**
     * Builds the full [ProfileView] for a player: every registered
     * [org.helix.api.addon.ProfileInfoProvider]'s lines, plus every
     * registered [org.helix.api.addon.ProfileSettingProvider]'s settings
     * resolved against the player's stored choice (or the descriptor's
     * default).
     *
     * @param player player name.
     * @return the assembled view.
     */
    private fun buildView(player: String): ProfileView {
        val info = context.profileInfo(player)
        val resolved = context.profileSettings(player).flatMap { (owner, descriptors) ->
            descriptors.map { descriptor ->
                val current = store.get(player, owner, descriptor.key) ?: descriptor.default
                ResolvedSetting(owner, descriptor, current)
            }
        }
        return ProfileView(player, info, resolved)
    }

    /**
     * Shared logic for `profile.setting.set` and `profile.setting.admin-set`.
     *
     * Self-service calls must target an unlocked [org.helix.api.addon.ProfileSettingOption]
     * for [ProfileSettingType.Choice] settings; staff overrides skip that
     * per-player gating (they still must name one of the descriptor's
     * declared options — staff cannot invent a value out of thin air).
     * Both paths run [org.helix.api.addon.ProfileSettingProvider.validate]
     * for checks the type alone cannot express.
     */
    private fun setSetting(invocation: ActionInvocation, asAdmin: Boolean): ActionResult {
        val args = invocation.arguments
        val player = args.getOrNull(0)
        val owner = args.getOrNull(1)
        val key = args.getOrNull(2)
        val value = args.getOrNull(3)
        if (player == null || owner == null || key == null || value == null) {
            return ActionResult.error("usage: ${invocation.action} <player> <owner> <key> <value>")
        }
        val descriptor = context.profileSettings(player)[owner]?.find { it.key == key }
            ?: return ActionResult.error("unknown setting: $owner:$key")

        when (val type = descriptor.type) {
            is ProfileSettingType.Toggle ->
                if (value != "true" && value != "false") {
                    return ActionResult.error("a toggle only accepts true/false")
                }
            is ProfileSettingType.Choice -> {
                val option = type.options.find { it.id == value }
                    ?: return ActionResult.error("not one of this setting's options: $value")
                if (!asAdmin && !option.unlocked) {
                    return ActionResult.error("locked")
                }
            }
            is ProfileSettingType.FreeText ->
                if (value.length > type.maxLength) {
                    return ActionResult.error("value exceeds the ${type.maxLength}-character limit")
                }
        }

        context.validateProfileSetting(owner, player, key, value)?.let { reason ->
            return ActionResult.error(reason)
        }

        store.set(player, owner, key, value)
        context.notifyProfileSettingChanged(owner, player, key, value)
        return ActionResult.ok(descriptor.label)
    }

    private fun defaultFor(player: String, owner: String, key: String): String =
        context.profileSettings(player)[owner]?.find { it.key == key }?.default ?: ""

    /**
     * Dispatches the `/profile` in-game command: no arguments shows a
     * readable text summary (the fallback UI on servers without a Paper
     * GUI component installed); `set <key> <value>` resolves the owning
     * addon by scanning every registered setting for a matching key and
     * delegates to `profile.setting.set`.
     */
    private fun profileCommand(invocation: ActionInvocation): ActionResult {
        val executor = invocation.arguments.firstOrNull()
            ?: return ActionResult.error("missing executing player")
        val rest = invocation.arguments.drop(1)
        if (rest.isEmpty()) {
            return renderTextSummary(executor)
        }
        if (rest.first().equals("set", ignoreCase = true)) {
            val key = rest.getOrNull(1)
            val value = rest.getOrNull(2)
            if (key == null || value == null) {
                return ActionResult.error(msg.formatFor(executor, "usage"))
            }
            val owner = context.profileSettings(executor).entries
                .firstOrNull { (_, descriptors) -> descriptors.any { it.key == key } }
                ?.key
                ?: return ActionResult.error(msg.formatFor(executor, "unknown.key", "key" to key))
            val result = setSetting(
                ActionInvocation("profile.setting.set", listOf(executor, owner, key, value)),
                asAdmin = false,
            )
            return if (result.success) {
                ActionResult.ok(msg.formatFor(executor, "changed", "label" to result.lines.first(), "value" to value))
            } else {
                val reason = result.lines.firstOrNull().orEmpty()
                if (reason == "locked") {
                    ActionResult.error(msg.formatFor(executor, "locked"))
                } else {
                    ActionResult.error(msg.formatFor(executor, "rejected", "reason" to reason))
                }
            }
        }
        return ActionResult.error(msg.formatFor(executor, "usage"))
    }

    private fun renderTextSummary(player: String): ActionResult {
        val view = buildView(player)
        val lines = buildList {
            add(msg.formatFor(player, "header"))
            view.info.values.flatten().forEach { entry ->
                add(msg.formatFor(player, "info.line", "label" to entry.label, "value" to entry.value))
            }
            view.settings.forEach { setting ->
                add(
                    msg.formatFor(
                        player,
                        "setting.line",
                        "label" to setting.descriptor.label,
                        "key" to setting.descriptor.key,
                        "value" to setting.current,
                    ),
                )
            }
        }
        return ActionResult.ok(*lines.toTypedArray())
    }
}
