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
    private lateinit var msg: org.helix.api.message.Messages

    /**
     * Registers the `/friend` player command and the join listener.
     */
    override fun enable() {
        store = FriendStore(context.storage())
        msg = context.messages(
            mapOf(
                "joined" to "&aYour friend &f{player} &ajoined the network.",
                "request.sent" to "&eFriend request sent to {target}.",
                "request.received" to "&e{sender} wants to be your friend. &7/friend accept {sender}",
                "accepted.self" to "&aYou are now friends with {target}.",
                "accepted.other" to "&a{player} accepted your friend request.",
                "denied" to "&7Denied the request from {target}.",
                "removed" to "&7You are no longer friends with {target}.",
                "error.self" to "&cYou cannot add yourself.",
                "error.already" to "&cYou are already friends with {target}.",
                "error.duplicate" to "&cYou already sent a request to {target}.",
                "error.norequest" to "&cNo pending request from {target}.",
                "error.notfriends" to "&cYou are not friends with {target}.",
                "requests.none" to "&7No pending friend requests.",
                "requests.list" to "&ePending requests: &f{players}",
                "list.empty" to "&7You have no friends yet. &f/friend add <player>",
                "list.online" to "&aonline",
                "list.offline" to "&8offline",
                "list.entry" to "&f{friend} &7— {status}",
            ),
        )
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
                            message(friend, msg.format("joined", "player" to player.name))
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
            return ActionResult.error(msg.format("error.self"))
        }
        if (store.areFriends(executor, target)) {
            return ActionResult.error(msg.format("error.already", "target" to target))
        }
        if (store.hasRequest(target, executor)) {
            store.accept(executor, target)
            message(target, msg.format("accepted.other", "player" to executor))
            return ActionResult.ok(msg.format("accepted.self", "target" to target))
        }
        if (!store.request(executor, target)) {
            return ActionResult.error(msg.format("error.duplicate", "target" to target))
        }
        message(target, msg.format("request.received", "sender" to executor))
        return ActionResult.ok(msg.format("request.sent", "target" to target))
    }

    private fun accept(executor: String, target: String): ActionResult {
        if (!store.accept(executor, target)) {
            return ActionResult.error(msg.format("error.norequest", "target" to target))
        }
        message(target, msg.format("accepted.other", "player" to executor))
        return ActionResult.ok(msg.format("accepted.self", "target" to target))
    }

    private fun deny(executor: String, target: String): ActionResult =
        if (store.deny(executor, target)) {
            ActionResult.ok(msg.format("denied", "target" to target))
        } else {
            ActionResult.error(msg.format("error.norequest", "target" to target))
        }

    private fun remove(executor: String, target: String): ActionResult =
        if (store.remove(executor, target)) {
            ActionResult.ok(msg.format("removed", "target" to target))
        } else {
            ActionResult.error(msg.format("error.notfriends", "target" to target))
        }

    private fun requests(executor: String): ActionResult {
        val pending = store.requestsFor(executor)
        return if (pending.isEmpty()) {
            ActionResult.ok(msg.format("requests.none"))
        } else {
            ActionResult.ok(msg.format("requests.list", "players" to pending.joinToString()))
        }
    }

    private fun list(executor: String): ActionResult {
        val friends = store.friendsOf(executor)
        if (friends.isEmpty()) {
            return ActionResult.ok(msg.format("list.empty"))
        }
        val online = context.onlinePlayers().map { it.name.lowercase() }.toSet()
        return ActionResult.ok(
            *friends.map { friend ->
                val status = msg.format(if (friend in online) "list.online" else "list.offline")
                msg.format("list.entry", "friend" to friend, "status" to status)
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
