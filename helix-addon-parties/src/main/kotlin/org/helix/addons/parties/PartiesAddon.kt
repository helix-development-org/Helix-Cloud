package org.helix.addons.parties

import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource

/**
 * Network-wide party addon.
 *
 * A lightweight, in-memory grouping of players below the level of a clan: a
 * leader plus a member list, invite/accept/leave/kick, useful for grouping
 * players across queueing/matchmaking style tasks. The in-game `/party`
 * command drives it directly; `party.members` lets other addons or external
 * Paper plugins resolve a player's current group without needing to know
 * about parties at all — a player without one counts as a party of one.
 */
class PartiesAddon : AddonBase() {
    private val manager = PartyManager()
    private lateinit var msg: org.helix.api.message.Messages

    /**
     * Registers the `/party` player command and the `party.members` query action.
     */
    override fun enable() {
        msg = loadMessages()
        action(
            "party",
            "Party system: create, invite, accept, leave, kick, list.",
            "party <create|invite|accept|leave|kick|list> [player]",
            playerCommand = true,
        ) { handlePartyCommand(it) }
        action(
            "party.members",
            "Resolves a player's current party members; a player without a party is a party of one.",
            "party.members <player>",
        ) { inv ->
            val player = inv.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: party.members <player>")
            val members = manager.partyOf(player)?.members ?: listOf(player.lowercase())
            ActionResult.ok(*members.toTypedArray())
        }
    }

    private fun handlePartyCommand(invocation: ActionInvocation): ActionResult {
        val executor = invocation.arguments.firstOrNull()
            ?: return ActionResult.error("missing executing player")
        val sub = invocation.arguments.getOrNull(1)?.lowercase() ?: "list"
        val target = invocation.arguments.getOrNull(2)
        return when (sub) {
            "create" -> create(executor)
            "invite" -> invite(executor, target ?: return usage(executor))
            "accept" -> accept(executor, target ?: return usage(executor))
            "leave" -> leave(executor)
            "kick" -> kick(executor, target ?: return usage(executor))
            "list" -> list(executor)
            else -> usage(executor)
        }
    }

    private fun create(executor: String): ActionResult =
        if (manager.create(executor) != null) {
            ActionResult.ok(msg.formatFor(executor, "created"))
        } else {
            ActionResult.error(msg.formatFor(executor, "error.already"))
        }

    private fun invite(executor: String, target: String): ActionResult {
        val party = manager.partyOf(executor) ?: return ActionResult.error(msg.formatFor(executor, "error.noparty"))
        if (party.leader != executor.lowercase()) {
            return ActionResult.error(msg.formatFor(executor, "error.notleader"))
        }
        if (manager.partyOf(target) != null) {
            return ActionResult.error(msg.formatFor(executor, "error.target.inparty", "target" to target))
        }
        if (!manager.invite(executor, target)) {
            return ActionResult.error(msg.formatFor(executor, "error.duplicate.invite", "target" to target))
        }
        message(target, msg.formatFor(target, "invite.received", "leader" to executor))
        return ActionResult.ok(msg.formatFor(executor, "invited", "target" to target))
    }

    private fun accept(executor: String, leader: String): ActionResult {
        if (!manager.accept(executor, leader)) {
            return ActionResult.error(msg.formatFor(executor, "error.noinvite", "leader" to leader))
        }
        manager.partyOf(executor)?.members
            ?.filter { it != executor.lowercase() }
            ?.forEach { member -> message(member, msg.formatFor(member, "accepted.other", "player" to executor)) }
        return ActionResult.ok(msg.formatFor(executor, "accepted.self", "leader" to leader))
    }

    private fun leave(executor: String): ActionResult {
        val before = manager.partyOf(executor) ?: return ActionResult.error(msg.formatFor(executor, "error.noparty"))
        val wasLeader = before.leader == executor.lowercase()
        manager.leave(executor)
        val remainingNames = before.members.filter { it != executor.lowercase() }
        remainingNames.forEach { member -> message(member, msg.formatFor(member, "left.other", "player" to executor)) }
        if (wasLeader) {
            val newLeader = manager.partyOf(remainingNames.firstOrNull() ?: "")?.leader
            if (newLeader != null) {
                remainingNames.forEach { member ->
                    message(member, msg.formatFor(member, "promoted", "player" to newLeader))
                }
            }
        }
        return ActionResult.ok(msg.formatFor(executor, "left.self"))
    }

    private fun kick(executor: String, target: String): ActionResult {
        val party = manager.partyOf(executor) ?: return ActionResult.error(msg.formatFor(executor, "error.noparty"))
        if (party.leader != executor.lowercase()) {
            return ActionResult.error(msg.formatFor(executor, "error.notleader"))
        }
        if (target.equals(executor, ignoreCase = true)) {
            return ActionResult.error(msg.formatFor(executor, "error.kick.self"))
        }
        if (!manager.kick(executor, target)) {
            return ActionResult.error(msg.formatFor(executor, "error.notmember", "target" to target))
        }
        message(target, msg.formatFor(target, "kicked.self"))
        party.members.filter { it != executor.lowercase() && it != target.lowercase() }
            .forEach { member -> message(member, msg.formatFor(member, "kicked.other", "target" to target)) }
        return ActionResult.ok(msg.formatFor(executor, "kicked.other", "target" to target))
    }

    private fun list(executor: String): ActionResult {
        val party = manager.partyOf(executor) ?: return ActionResult.ok(msg.formatFor(executor, "list.empty"))
        return ActionResult.ok(
            msg.formatFor(executor, "list.header", "leader" to party.leader),
            *party.members.map { msg.formatFor(executor, "list.member", "member" to it) }.toTypedArray(),
        )
    }

    private fun usage(executor: String): ActionResult = ActionResult.error(msg.formatFor(executor, "usage"))

    private fun message(player: String, text: String) {
        context.actions.invoke(ActionInvocation("player.message", listOf(player, text), ActionSource.ADDON))
    }
}
