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
        store = FriendStore(context.storage(), resolveUuid = context::resolvePlayerUuid)
        msg = loadMessages()
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
                            message(friend, msg.formatFor(friend, "joined", "player" to player.name))
                        }
                }
            },
        )
        context.registerPlayerDataProvider(
            /** Exports the player's friends/requests; forgets all of it on delete. */
            object : org.helix.api.addon.PlayerDataProvider {
                override fun export(player: String): String? {
                    val friends = store.friendsOf(player)
                    val requests = store.requestsFor(player)
                    if (friends.isEmpty() && requests.isEmpty()) {
                        return null
                    }
                    return kotlinx.serialization.json.Json.encodeToString(
                        FriendExport(friends = friends, incomingRequests = requests),
                    )
                }

                override fun delete(player: String): Boolean = store.forget(player)
            },
        )
    }

    private fun handleFriendCommand(invocation: ActionInvocation): ActionResult {
        val executor = invocation.arguments.firstOrNull()
            ?: return ActionResult.error("missing executing player")
        val sub = invocation.arguments.getOrNull(1)?.lowercase() ?: "list"
        val target = invocation.arguments.getOrNull(2)
        return when (sub) {
            "add" -> add(executor, target ?: return usage(executor))
            "accept" -> accept(executor, target ?: return usage(executor))
            "deny" -> deny(executor, target ?: return usage(executor))
            "remove" -> remove(executor, target ?: return usage(executor))
            "requests" -> requests(executor)
            "list" -> list(executor)
            else -> usage(executor)
        }
    }

    private fun add(executor: String, target: String): ActionResult {
        if (executor.equals(target, ignoreCase = true)) {
            return ActionResult.error(msg.formatFor(executor, "error.self"))
        }
        if (store.areFriends(executor, target)) {
            return ActionResult.error(msg.formatFor(executor, "error.already", "target" to target))
        }
        if (store.hasRequest(target, executor)) {
            store.accept(executor, target)
            message(target, msg.formatFor(target, "accepted.other", "player" to executor))
            return ActionResult.ok(msg.formatFor(executor, "accepted.self", "target" to target))
        }
        when (store.request(executor, target)) {
            FriendRequestOutcome.ALREADY_PENDING ->
                return ActionResult.error(msg.formatFor(executor, "error.duplicate", "target" to target))
            FriendRequestOutcome.COOLDOWN ->
                return ActionResult.error(msg.formatFor(executor, "error.cooldown", "target" to target))
            FriendRequestOutcome.SENT -> Unit
        }
        message(target, msg.formatFor(target, "request.received", "sender" to executor))
        return ActionResult.ok(msg.formatFor(executor, "request.sent", "target" to target))
    }

    private fun accept(executor: String, target: String): ActionResult {
        if (!store.accept(executor, target)) {
            return ActionResult.error(msg.formatFor(executor, "error.norequest", "target" to target))
        }
        message(target, msg.formatFor(target, "accepted.other", "player" to executor))
        return ActionResult.ok(msg.formatFor(executor, "accepted.self", "target" to target))
    }

    private fun deny(executor: String, target: String): ActionResult =
        if (store.deny(executor, target)) {
            ActionResult.ok(msg.formatFor(executor, "denied", "target" to target))
        } else {
            ActionResult.error(msg.formatFor(executor, "error.norequest", "target" to target))
        }

    private fun remove(executor: String, target: String): ActionResult =
        if (store.remove(executor, target)) {
            ActionResult.ok(msg.formatFor(executor, "removed", "target" to target))
        } else {
            ActionResult.error(msg.formatFor(executor, "error.notfriends", "target" to target))
        }

    private fun requests(executor: String): ActionResult {
        val pending = store.requestsFor(executor)
        return if (pending.isEmpty()) {
            ActionResult.ok(msg.formatFor(executor, "requests.none"))
        } else {
            ActionResult.ok(msg.formatFor(executor, "requests.list", "players" to pending.joinToString()))
        }
    }

    private fun list(executor: String): ActionResult {
        val friends = store.friendsOf(executor)
        if (friends.isEmpty()) {
            return ActionResult.ok(msg.formatFor(executor, "list.empty"))
        }
        val online = context.onlinePlayers().map { it.name.lowercase() }.toSet()
        return ActionResult.ok(
            *friends.map { friend ->
                val status = msg.formatFor(executor, if (friend in online) "list.online" else "list.offline")
                msg.formatFor(executor, "list.entry", "friend" to friend, "status" to status)
            }.toTypedArray(),
        )
    }

    private fun usage(executor: String): ActionResult =
        ActionResult.error(msg.formatFor(executor, "usage"))

    private fun message(player: String, text: String) {
        context.actions.invoke(
            ActionInvocation("player.message", listOf(player, text), ActionSource.ADDON),
        )
    }
}
