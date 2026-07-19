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
    /**
     * Registers the team commands and the join/leave notifications.
     */
    override fun enable() {
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
                val delivered = notifyTeam("&b[Team] &f$executor&7: &f$message")
                if (delivered == 0) ActionResult.ok("&7No team members online.") else ActionResult.ok()
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
                ActionResult.ok("&7No team members online.")
            } else {
                ActionResult.ok("&bOnline team: &f${members.joinToString { it.name }}")
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
                val delivered = notifyTeam("&b[Team] &f$text")
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
                        notifyTeam("&b[Team] &f${player.name} &7is now &aonline&7.", exclude = player.name)
                    }
                }

                override fun onLeave(player: OnlinePlayer) {
                    if (context.hasPermission(player.name, TEAM_PERMISSION)) {
                        notifyTeam("&b[Team] &f${player.name} &7is now &8offline&7.", exclude = player.name)
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
