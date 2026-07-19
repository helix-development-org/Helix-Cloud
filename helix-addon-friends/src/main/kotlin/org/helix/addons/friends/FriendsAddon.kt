package org.helix.addons.friends

import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.addon.PlayerListener
import org.helix.api.player.OnlinePlayer

/**
 * Friend system addon.
 *
 * Provides the in-game `/friend` command (requests, accept/deny, list with
 * online status) and notifies online friends when a friend joins the
 * network. All messaging runs through the generic `player.message` action.
 */
class FriendsAddon : AddonBase() {
    private lateinit var store: FriendStore

    /**
     * Registers the `/friend` player command and the join listener.
     */
    override fun enable() {
        store = FriendStore(context.dataDirectory.resolve("friends.json"))
        context.registerAction(
            ActionDescriptor(
                name = "friend",
                description = "Friend system: requests, accept/deny, list.",
                usage = "friend <add|accept|deny|remove|list|requests> [player]",
                playerCommand = true,
            ),
            ::handleFriendCommand,
        )
        context.registerPlayerListener(
            /** Notifies online friends about joins. */
            object : PlayerListener {
                override fun onJoin(player: OnlinePlayer) {
                    val online = context.onlinePlayers().map { it.name.lowercase() }.toSet()
                    store.friendsOf(player.name)
                        .filter { it in online }
                        .forEach { friend ->
                            message(friend, "&aYour friend &f${player.name} &ajoined the network.")
                        }
                }
            },
        )
    }

    private fun handleFriendCommand(invocation: ActionInvocation): ActionResult {
        val executor = invocation.arguments.firstOrNull()
            ?: return ActionResult.error("missing executing player")
        val sub = invocation.arguments.getOrNull(1)?.lowercase() ?: "list"
        val target = invocation.arguments.getOrNull(2)
        return when (sub) {
            "add" -> add(executor, target ?: return usage())
            "accept" -> accept(executor, target ?: return usage())
            "deny" -> deny(executor, target ?: return usage())
            "remove" -> remove(executor, target ?: return usage())
            "requests" -> requests(executor)
            "list" -> list(executor)
            else -> usage()
        }
    }

    private fun add(executor: String, target: String): ActionResult {
        if (executor.equals(target, ignoreCase = true)) {
            return ActionResult.error("You cannot add yourself.")
        }
        if (store.areFriends(executor, target)) {
            return ActionResult.error("You are already friends with $target.")
        }
        if (store.hasRequest(target, executor)) {
            store.accept(executor, target)
            message(target, "&a$executor accepted your friend request.")
            return ActionResult.ok("&aYou are now friends with $target.")
        }
        if (!store.request(executor, target)) {
            return ActionResult.error("You already sent a request to $target.")
        }
        message(target, "&e$executor wants to be your friend. &7/friend accept $executor")
        return ActionResult.ok("&eFriend request sent to $target.")
    }

    private fun accept(executor: String, target: String): ActionResult {
        if (!store.accept(executor, target)) {
            return ActionResult.error("No pending request from $target.")
        }
        message(target, "&a$executor accepted your friend request.")
        return ActionResult.ok("&aYou are now friends with $target.")
    }

    private fun deny(executor: String, target: String): ActionResult =
        if (store.deny(executor, target)) {
            ActionResult.ok("&7Denied the request from $target.")
        } else {
            ActionResult.error("No pending request from $target.")
        }

    private fun remove(executor: String, target: String): ActionResult =
        if (store.remove(executor, target)) {
            ActionResult.ok("&7You are no longer friends with $target.")
        } else {
            ActionResult.error("You are not friends with $target.")
        }

    private fun requests(executor: String): ActionResult {
        val pending = store.requestsFor(executor)
        return if (pending.isEmpty()) {
            ActionResult.ok("&7No pending friend requests.")
        } else {
            ActionResult.ok("&ePending requests: &f${pending.joinToString()}")
        }
    }

    private fun list(executor: String): ActionResult {
        val friends = store.friendsOf(executor)
        if (friends.isEmpty()) {
            return ActionResult.ok("&7You have no friends yet. &f/friend add <player>")
        }
        val online = context.onlinePlayers().map { it.name.lowercase() }.toSet()
        return ActionResult.ok(
            *friends.map { friend ->
                val status = if (friend in online) "&aonline" else "&8offline"
                "&f$friend &7— $status"
            }.toTypedArray(),
        )
    }

    private fun usage(): ActionResult =
        ActionResult.error("Usage: /friend <add|accept|deny|remove|list|requests> [player]")

    private fun message(player: String, text: String) {
        context.actions.invoke(
            ActionInvocation("player.message", listOf(player, text), ActionSource.ADDON),
        )
    }
}
