package org.helix.addons.permissions

import org.helix.api.release.ReleaseReport
import org.helix.api.release.ReleaseReset
import org.helix.api.action.ActionResult
import org.helix.api.design.PlayerRanks
import org.helix.api.message.Messages

/**
 * Closed test phase access: the seeded `tester` group and the team command
 * `/tester add|remove|list|info <player> [duration]`.
 *
 * Testers join while the network is in maintenance through the group's
 * `helix.maintenance.bypass`; everything else a tester may do is granted by
 * the feedback and test-tool permissions of that same group.
 *
 * @property store permission store holding groups and users.
 * @property messages translated texts (`tester.*`).
 * @property clock current time in epoch millis (injectable for tests).
 */
class TesterAccess(
    private val store: PermissionStore,
    private val messages: Messages,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Creates the `tester` group when it does not exist yet. An existing
     * group (also one edited by operators) is never touched.
     *
     * @return `true` when the group was created.
     */
    fun seedGroup(): Boolean {
        if (store.group(GROUP) != null) {
            return false
        }
        val rank = PlayerRanks.TESTER
        val color = "<#%06x>".format(rank.color)
        store.saveGroup(
            PermissionGroup(
                name = GROUP,
                weight = rank.weight,
                default = false,
                permissions = PERMISSIONS,
                prefix = "$color${rank.tag}</#%06x> ".format(rank.color),
                color = color,
            ),
        )
        return true
    }

    /**
     * Runs the `/tester` command.
     *
     * @param executor name of the team member running the command.
     * @param args arguments after the executor name.
     * @return translated reply lines.
     */
    fun command(executor: String, args: List<String>): ActionResult {
        if (store.group(GROUP) == null) {
            seedGroup()
        }
        val sub = args.firstOrNull()?.lowercase()
        val player = args.getOrNull(1)
        return when (sub) {
            "add" -> player?.let { add(executor, it, args.getOrNull(2)) } ?: usage(executor)
            "remove" -> player?.let { remove(executor, it) } ?: usage(executor)
            "info" -> player?.let { info(executor, it) } ?: usage(executor)
            "list" -> list(executor)
            else -> usage(executor)
        }
    }

    /**
     * Lists all current testers with their remaining time.
     *
     * @return `name` to remaining millis (`null` = permanent), sorted by name.
     */
    fun testers(): List<Pair<String, Long?>> {
        val now = clock()
        return store.document().users.mapNotNull { stored ->
            val user = store.user(stored.name, stored.uuid)
            when {
                user.groups.any { it.equals(GROUP, ignoreCase = true) } -> user.name to null
                else -> user.timedGroups.firstOrNull { it.value.equals(GROUP, ignoreCase = true) && it.active(now) }
                    ?.let { user.name to (it.expiresAtEpochMs - now) }
            }
        }.sortedBy { it.first }
    }

    /**
     * Release step of the closed test phase: gives every current tester the permanent beta badge (group
     * [BADGE_GROUP] with [PlayerRanks.BETA_TESTER_BADGE]) and optionally removes all `tester` memberships.
     *
     * @param dryRun `true` only counts.
     * @param removeTesters also remove the `tester` group from every member.
     * @return the report of this step.
     */
    fun release(dryRun: Boolean, removeTesters: Boolean): ReleaseReport {
        val members = testers().map { it.first }
        val createGroup = store.group(BADGE_GROUP) == null
        if (!dryRun) {
            seedBadgeGroup()
            members.forEach { name ->
                val user = store.user(name)
                val groups = (user.groups + BADGE_GROUP).distinct()
                    .let { all -> if (removeTesters) all.filter { !it.equals(GROUP, ignoreCase = true) } else all }
                val timed = if (removeTesters) user.timedGroups.filter { !it.value.equals(GROUP, ignoreCase = true) } else user.timedGroups
                store.saveUser(user.copy(groups = groups, timedGroups = timed))
            }
        }
        return ReleaseReport(
            addon = "permissions",
            mode = if (dryRun) ReleaseReset.PLAN else ReleaseReset.RUN,
            deleted = if (removeTesters) linkedMapOf("tester_memberships" to members.size) else emptyMap(),
            kept = buildMap {
                put("groups", "all groups and user grants")
                if (!removeTesters) put("tester_memberships", "${members.size} testers keep the tester group")
            },
            notes = listOfNotNull(
                "beta badge ($BADGE_GROUP) for ${members.size} tester(s)",
                if (createGroup) "group $BADGE_GROUP will be created" else null,
            ),
        )
    }

    /**
     * Creates the permanent badge group [BADGE_GROUP] when missing (weight 1, so every real rank prefix wins).
     *
     * @return `true` when the group was created.
     */
    fun seedBadgeGroup(): Boolean {
        if (store.group(BADGE_GROUP) != null) {
            return false
        }
        val rank = PlayerRanks.TESTER
        store.saveGroup(
            PermissionGroup(
                name = BADGE_GROUP,
                weight = 1,
                default = false,
                permissions = listOf(PlayerRanks.BETA_TESTER_BADGE),
                prefix = "<#%06x>[Beta]</#%06x> ".format(rank.color, rank.color),
                color = "<#%06x>".format(rank.color),
            ),
        )
        return true
    }

    private fun add(executor: String, player: String, durationToken: String?): ActionResult {
        val user = store.user(player)
        if (durationToken.isNullOrBlank()) {
            store.saveUser(
                user.copy(
                    groups = (user.groups + GROUP).distinct(),
                    timedGroups = user.timedGroups.filter { !it.value.equals(GROUP, ignoreCase = true) },
                ),
            )
            return ActionResult.ok(messages.formatFor(executor, "tester.added", "player" to user.name))
        }
        val millis = GrantDuration.parseMillis(durationToken)
            ?: return ActionResult.error(messages.formatFor(executor, "tester.error.duration", "duration" to durationToken))
        store.saveUser(
            user.copy(
                groups = user.groups.filter { !it.equals(GROUP, ignoreCase = true) },
                timedGroups = user.timedGroups.filter { !it.value.equals(GROUP, ignoreCase = true) } +
                    TimedGrant(GROUP, clock() + millis),
            ),
        )
        return ActionResult.ok(
            messages.formatFor(
                executor,
                "tester.added.timed",
                "player" to user.name,
                "duration" to GrantDuration.format(millis),
            ),
        )
    }

    private fun remove(executor: String, player: String): ActionResult {
        val user = store.user(player)
        val member = user.groups.any { it.equals(GROUP, ignoreCase = true) } ||
            user.timedGroups.any { it.value.equals(GROUP, ignoreCase = true) }
        if (!member) {
            return ActionResult.error(messages.formatFor(executor, "tester.error.not-tester", "player" to user.name))
        }
        store.saveUser(
            user.copy(
                groups = user.groups.filter { !it.equals(GROUP, ignoreCase = true) },
                timedGroups = user.timedGroups.filter { !it.value.equals(GROUP, ignoreCase = true) },
            ),
        )
        return ActionResult.ok(messages.formatFor(executor, "tester.removed", "player" to user.name))
    }

    private fun info(executor: String, player: String): ActionResult {
        val entry = testers().firstOrNull { it.first.equals(player, ignoreCase = true) }
            ?: return ActionResult.ok(messages.formatFor(executor, "tester.info.none", "player" to player.lowercase()))
        return ActionResult.ok(line(executor, entry))
    }

    private fun list(executor: String): ActionResult {
        val all = testers()
        if (all.isEmpty()) {
            return ActionResult.ok(messages.formatFor(executor, "tester.list.empty"))
        }
        val lines = listOf(messages.formatFor(executor, "tester.list.header", "count" to all.size.toString())) +
            all.map { line(executor, it) }
        return ActionResult.ok(*lines.toTypedArray())
    }

    private fun line(executor: String, entry: Pair<String, Long?>): String = entry.second
        ?.let { messages.formatFor(executor, "tester.entry.timed", "player" to entry.first, "remaining" to GrantDuration.format(it)) }
        ?: messages.formatFor(executor, "tester.entry.permanent", "player" to entry.first)

    private fun usage(executor: String): ActionResult = ActionResult.error(messages.formatFor(executor, "tester.usage"))

    /** Group name and default permissions of the closed test phase. */
    companion object {
        /** Name of the tester group. */
        const val GROUP: String = "tester"

        /** Permanent badge group of former testers. */
        const val BADGE_GROUP: String = "betatester"

        /** Team permission for the `/tester` command. */
        const val MANAGE_PERMISSION: String = "tester.manage"

        /** Permissions seeded into a new `tester` group. */
        val PERMISSIONS: List<String> = listOf(
            "helix.maintenance.bypass",
            "feedback.report",
            "feedback.tester",
            "tester.tools",
        )
    }
}
