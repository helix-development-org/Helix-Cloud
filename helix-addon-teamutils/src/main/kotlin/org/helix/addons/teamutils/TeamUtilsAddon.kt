package org.helix.addons.teamutils

import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.addon.PlayerListener
import org.helix.api.player.OnlinePlayer

/**
 * Team utilities addon.
 *
 * Team membership is the permission `helix.team.member` — managed by any
 * installed permission system. Provides team chat (`/tc`), a team member
 * list (`/team`), join/leave notifications for team members and the
 * `team.notify` action for CLI and dashboard announcements.
 *
 * Additionally forwards every `moderation` notification (bans, warns,
 * kicks published by other addons) to all online team members, so staff
 * sees moderation activity live in chat.
 */
class TeamUtilsAddon : AddonBase() {
    private lateinit var msg: org.helix.api.message.Messages

    /**
     * Registers the team commands and the join/leave notifications.
     */
    override fun enable() {
        msg = context.messages(
            mapOf(
                "chat" to "&b[Team] &f{sender}&7: &f{message}",
                "empty" to "&7No team members online.",
                "list" to "&bOnline team: &f{members}",
                "notify" to "&b[Team] &f{text}",
                "join" to "&b[Team] &f{player} &7is now &aonline&7.",
                "leave" to "&b[Team] &f{player} &7is now &8offline&7.",
            ),
        )
        context.registerAction(
            ActionDescriptor(
                name = "tc",
                description = "Sends a message to all online team members.",
                usage = "tc <message...>",
                playerCommand = true,
                permission = TEAM_PERMISSION,
            ),
        ) { invocation ->
            val executor = invocation.arguments.firstOrNull()
                ?: return@registerAction ActionResult.error("missing executing player")
            val message = invocation.arguments.drop(1).joinToString(" ")
            if (message.isBlank()) {
                ActionResult.error("Usage: /tc <message...>")
            } else {
                val delivered = notifyTeam(msg.format("chat", "sender" to executor, "message" to message))
                if (delivered == 0) ActionResult.ok(msg.format("empty")) else ActionResult.ok()
            }
        }
        context.registerAction(
            ActionDescriptor(
                name = "team",
                description = "Lists all online team members.",
                usage = "team",
                playerCommand = true,
                permission = TEAM_PERMISSION,
            ),
        ) {
            val members = onlineTeamMembers()
            if (members.isEmpty()) {
                ActionResult.ok(msg.format("empty"))
            } else {
                ActionResult.ok(msg.format("list", "members" to members.joinToString { it.name }))
            }
        }
        action(
            "team.notify",
            "Sends a notification to all online team members.",
            "team.notify <text...>",
        ) { invocation ->
            val text = invocation.arguments.joinToString(" ")
            if (text.isBlank()) {
                ActionResult.error("usage: team.notify <text...>")
            } else {
                val delivered = notifyTeam(msg.format("notify", "text" to text))
                ActionResult.ok("notified $delivered team members")
            }
        }
        context.registerNotificationListener { category, message ->
            if (category == "moderation") {
                notifyTeam(message)
            }
        }
        context.registerPlayerListener(
            /** Notifies the team about joining and leaving team members. */
            object : PlayerListener {
                override fun onJoin(player: OnlinePlayer) {
                    if (context.hasPermission(player.name, TEAM_PERMISSION)) {
                        notifyTeam(msg.format("join", "player" to player.name), exclude = player.name)
                    }
                }

                override fun onLeave(player: OnlinePlayer) {
                    if (context.hasPermission(player.name, TEAM_PERMISSION)) {
                        notifyTeam(msg.format("leave", "player" to player.name), exclude = player.name)
                    }
                }
            },
        )
    }

    private fun onlineTeamMembers(): List<OnlinePlayer> =
        context.onlinePlayers().filter { context.hasPermission(it.name, TEAM_PERMISSION) }

    private fun notifyTeam(text: String, exclude: String? = null): Int {
        val members = onlineTeamMembers().filter { !it.name.equals(exclude, ignoreCase = true) }
        members.forEach { member ->
            context.actions.invoke(
                ActionInvocation("player.message", listOf(member.name, text), ActionSource.ADDON),
            )
        }
        return members.size
    }

    private companion object {
        /** Permission marking a player as team member. */
        const val TEAM_PERMISSION = "helix.team.member"
    }
}
