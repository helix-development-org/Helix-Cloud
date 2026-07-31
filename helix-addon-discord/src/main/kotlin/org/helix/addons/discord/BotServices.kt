package org.helix.addons.discord

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.addon.AddonInfo
import org.helix.api.player.OnlinePlayer

/**
 * Everything the Discord bot's screens and interaction handlers share:
 * configuration, the node's action entry point, links, permission gate,
 * confirmations, audit trail and translations.
 *
 * @property config supplies the current configuration.
 * @property saveConfig persists a changed configuration.
 * @property actions the node's action entry point.
 * @property catalog action read model for the browser.
 * @property links confirmed account links.
 * @property gate permission decisions for Discord users.
 * @property confirmations pending second clicks.
 * @property audit the Discord audit trail.
 * @property texts translation helper.
 * @property onlinePlayers lists the network's online players.
 * @property installedAddons lists all installed addons.
 * @property resolveUuid resolves a player name to its uuid.
 * @property lastKnownName resolves a uuid to its last-known name.
 * @property now clock, injectable for tests.
 */
class BotServices(
    val config: () -> DiscordConfig,
    val saveConfig: (DiscordConfig) -> Unit,
    val actions: ActionInvoker,
    val catalog: ActionCatalog,
    val links: LinkStore,
    val gate: PermissionGate,
    val confirmations: ConfirmationManager,
    val audit: AuditLog,
    val texts: DiscordMessages,
    val onlinePlayers: () -> List<OnlinePlayer>,
    val installedAddons: () -> List<AddonInfo>,
    val resolveUuid: (String) -> String?,
    val lastKnownName: (String) -> String?,
    val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Runs a read-only action for a screen render, without an acting user.
     *
     * @param action the action name.
     * @param args the action arguments.
     * @return the action result.
     */
    fun run(action: String, vararg args: String): ActionResult =
        actions.invoke(ActionInvocation(action, args.toList(), ActionSource.ADDON))

    /**
     * Runs an action on behalf of a linked Minecraft account; player
     * commands receive the acting player as their first argument, exactly
     * like an in-game invocation.
     *
     * @param actorName the linked Minecraft name.
     * @param descriptor the action to run.
     * @param args the action arguments.
     * @return the action result.
     */
    fun invokeAs(actorName: String, descriptor: ActionDescriptor, args: List<String>): ActionResult {
        val full = if (descriptor.playerCommand) listOf(actorName) + args else args
        return actions.invoke(ActionInvocation(descriptor.name, full, ActionSource.ADDON, actor = actorName))
    }
}
