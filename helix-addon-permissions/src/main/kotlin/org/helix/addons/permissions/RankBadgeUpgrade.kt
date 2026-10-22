package org.helix.addons.permissions

import org.helix.api.design.PlayerRanks
import org.helix.api.design.StaffRank
import org.helix.api.design.StaffRanks
import org.helix.api.storage.AddonStorage

/**
 * Moves the standard groups from the former text prefixes (`[Owner]` …) to the rank badges (DESIGN §9 AC3).
 *
 * Group seeds are non-destructive, so installations created before the badges keep their old prefixes. This upgrade
 * replaces a prefix only while it still equals the former standard text of its rank – prefixes the team changed stay
 * as they are (unless forced). It runs once per installation ([upgradeOnce], versioned in the addon storage) and on
 * demand via `perm.ranks.apply-badges`.
 *
 * @property store the permission store.
 * @property storage addon storage holding the upgrade version.
 */
class RankBadgeUpgrade(private val store: PermissionStore, private val storage: AddonStorage) {
    /**
     * Runs [apply] once per installation (tracked as [VERSION] under [VERSION_KEY]).
     *
     * @return the names of the changed groups (empty when already done).
     */
    fun upgradeOnce(): List<String> {
        val done = storage.read(VERSION_KEY)?.trim()?.toIntOrNull() ?: 0
        if (done >= VERSION) {
            return emptyList()
        }
        val changed = apply(force = false)
        storage.write(VERSION_KEY, VERSION.toString())
        return changed
    }

    /**
     * Sets the badge prefix (and rank colour, when unset) on every existing standard group whose prefix is still the
     * former standard text, or on all of them with [force].
     *
     * @param force also replace prefixes the team has changed.
     * @return the names of the changed groups.
     */
    fun apply(force: Boolean): List<String> {
        val changed = mutableListOf<String>()
        targets().forEach { (groupName, rank) ->
            val group = store.group(groupName) ?: return@forEach
            val replaceable = force || group.prefix == rank.legacyPrefix
            if (!replaceable || group.prefix == rank.prefix) {
                return@forEach
            }
            store.saveGroup(group.copy(prefix = rank.prefix, color = group.color.ifEmpty { rank.colorTag }))
            changed += group.name
        }
        return changed
    }

    /** Standard group name to rank: staff ranks (default group = `[Spieler]`), tester and beta badge group. */
    private fun targets(): List<Pair<String, StaffRank>> =
        StaffRanks.ALL.map { rank ->
            val name = if (rank.weight == 0) "default" else rank.tag.trim('[', ']').lowercase()
            name to rank
        } + listOf(TesterAccess.GROUP to PlayerRanks.TESTER, TesterAccess.BADGE_GROUP to PlayerRanks.BETA)

    /** Storage key, version and permission of the badge upgrade. */
    companion object {
        /** Addon-storage key of the applied upgrade version. */
        const val VERSION_KEY: String = "rank-badges-version"

        /** Current upgrade version (bump to re-run [upgradeOnce] after changing the standard prefixes). */
        const val VERSION: Int = 1

        /** Permission of `perm.ranks.apply-badges`. */
        const val PERMISSION: String = "helix.permissions.badges"
    }
}
