package org.helix.node.whitelist

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.node.actions.ActionRegistry

/**
 * Registers the whitelist actions, making the network allow-list manageable
 * from the CLI, the dashboard action console and the `/helix` player
 * command.
 *
 * @property whitelist the whitelist store.
 */
class WhitelistActions(private val whitelist: WhitelistStore) {
    /**
     * Registers `whitelist.mode`, `whitelist.add`, `whitelist.remove` and
     * `whitelist.list`.
     *
     * @param registry target registry.
     */
    fun registerAll(registry: ActionRegistry) {
        registry.register(
            ActionDescriptor(
                "whitelist.mode",
                "Shows or toggles the network whitelist.",
                "whitelist.mode [on|off]",
            ),
        ) { invocation ->
            when (invocation.arguments.firstOrNull()?.lowercase()) {
                null -> ActionResult.ok("whitelist: ${if (whitelist.isEnabled()) "on" else "off"}")
                "on" -> {
                    whitelist.setEnabled(true)
                    ActionResult.ok("whitelist enabled")
                }
                "off" -> {
                    whitelist.setEnabled(false)
                    ActionResult.ok("whitelist disabled")
                }
                else -> ActionResult.error("usage: whitelist.mode [on|off]")
            }
        }
        registry.register(
            ActionDescriptor("whitelist.add", "Adds an account to the whitelist.", "whitelist.add <player>"),
        ) { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@register ActionResult.error("usage: whitelist.add <player>")
            if (whitelist.add(player)) {
                ActionResult.ok("added $player to the whitelist")
            } else {
                ActionResult.error("$player is already whitelisted")
            }
        }
        registry.register(
            ActionDescriptor(
                "whitelist.remove",
                "Removes an account from the whitelist.",
                "whitelist.remove <player>",
            ),
        ) { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@register ActionResult.error("usage: whitelist.remove <player>")
            if (whitelist.remove(player)) {
                ActionResult.ok("removed $player from the whitelist")
            } else {
                ActionResult.error("$player is not whitelisted")
            }
        }
        registry.register(
            ActionDescriptor("whitelist.list", "Lists every whitelisted account.", "whitelist.list"),
        ) {
            val entries = whitelist.all()
            if (entries.isEmpty()) ActionResult.ok("whitelist is empty") else ActionResult.ok(*entries.toTypedArray())
        }
    }
}
