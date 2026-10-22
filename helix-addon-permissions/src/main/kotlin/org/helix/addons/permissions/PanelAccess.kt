package org.helix.addons.permissions

import kotlinx.serialization.Serializable
import org.helix.api.storage.AddonStorage

/**
 * One group in the dashboard access matrix (`perm.access.matrix`).
 *
 * @property name group name.
 * @property weight group weight (columns are ordered by it, highest first).
 * @property wildcard the group holds everything (`*`, `helix.*`, `helix.panel.*` or `helix.admin`) – shown locked.
 * @property granted matrix nodes the group holds itself (checkboxes).
 * @property inherited matrix nodes it only gets from a parent group or a wildcard below the top level
 *   (shown checked but not toggleable here).
 */
@Serializable
data class AccessGroup(
    val name: String,
    val weight: Int,
    val wildcard: Boolean,
    val granted: List<String>,
    val inherited: List<String>,
)

/**
 * A grantable node of the matrix that is not a dashboard permission (`helix.panel.*`) itself.
 *
 * @property node permission node.
 * @property label German label.
 * @property description German one-line explanation.
 */
@Serializable
data class AccessExtra(val node: String, val label: String, val description: String)

/**
 * Response of `perm.access.matrix`.
 *
 * @property groups every permission group, highest weight first.
 * @property extras additional team nodes the matrix may grant besides `helix.panel.*`.
 */
@Serializable
data class AccessMatrix(val groups: List<AccessGroup>, val extras: List<AccessExtra>)

/**
 * The dashboard access matrix and the per-rank presets.
 *
 * The matrix may only change nodes that decide what the dashboard shows and allows ([isMatrixNode]): the
 * `helix.panel.*` family plus the few [EXTRAS]. Groups holding a wildcard ([isWildcardGroup]) are locked – the
 * owner and manager ranks keep everything and cannot be narrowed from the matrix.
 *
 * [presetsOnce] gives the standard team groups and testers a sensible starting set once per installation, and only
 * to groups that have no dashboard node yet, so a choice made in the matrix is never overwritten.
 *
 * @property store the permission store.
 * @property storage addon storage holding the preset version.
 */
class PanelAccess(private val store: PermissionStore, private val storage: AddonStorage) {
    /**
     * The matrix state of every group.
     *
     * @return the matrix.
     */
    fun matrix(): AccessMatrix {
        val groups = store.allGroups().sortedByDescending { it.weight }.map { group ->
            val own = group.permissions.filter { isMatrixNode(it) }
            val chain = store.expand(group)
            val all = chain.flatMap { it.permissions }
            val wildcard = isWildcardGroup(all)
            // Nodes covered by a parent or a partial wildcard (helix.panel.services.*), but not granted directly.
            val candidates = (KNOWN_NODES + all.filter { isMatrixNode(it) }).toSet()
            val inherited = candidates.filter { node -> node !in own && PermissionMatcher.decide(all, node) == true }
            AccessGroup(group.name, group.weight, wildcard, own.sorted(), inherited.sorted())
        }
        return AccessMatrix(groups, EXTRAS)
    }

    /**
     * Grants or revokes one matrix node on a group.
     *
     * @param groupName group name.
     * @param node node to change; must pass [isMatrixNode].
     * @param grant `true` to grant, `false` to revoke.
     * @return `null` on success, otherwise a German error text.
     */
    fun set(groupName: String, node: String, grant: Boolean): String? {
        val group = store.group(groupName) ?: return "Unbekannte Gruppe: $groupName"
        if (!isMatrixNode(node)) return "Nur Dashboard-Rechte lassen sich hier ändern: $node"
        if (isWildcardGroup(store.expand(group).flatMap { it.permissions })) {
            return "Die Gruppe ${group.name} hat alle Rechte (Wildcard) und ist gesperrt"
        }
        val permissions = if (grant) (group.permissions + node).distinct() else group.permissions - node
        if (permissions != group.permissions) store.saveGroup(group.copy(permissions = permissions))
        return null
    }

    /**
     * Applies [PRESETS] once per installation (tracked as [VERSION] under [VERSION_KEY]).
     *
     * @return names of the groups that received their preset.
     */
    fun presetsOnce(): List<String> {
        val done = storage.read(VERSION_KEY)?.trim()?.toIntOrNull() ?: 0
        if (done >= VERSION) return emptyList()
        val changed = applyPresets()
        storage.write(VERSION_KEY, VERSION.toString())
        return changed
    }

    /**
     * Gives every existing preset group without any dashboard node its preset nodes (additive, keeps all other
     * permissions). Wildcard groups are left alone.
     *
     * @return names of the changed groups.
     */
    fun applyPresets(): List<String> {
        val changed = mutableListOf<String>()
        PRESETS.forEach { (name, nodes) ->
            val group = store.group(name) ?: return@forEach
            if (isWildcardGroup(group.permissions) || group.permissions.any { it.startsWith(PANEL_PREFIX) }) return@forEach
            store.saveGroup(group.copy(permissions = (group.permissions + nodes).distinct()))
            changed += group.name
        }
        return changed
    }

    /** Presets, matrix node rules and storage keys. */
    companion object {
        /** Prefix of every dashboard permission. */
        const val PANEL_PREFIX: String = "helix.panel."

        /** Permission of `perm.access.matrix` and `perm.access.set` (same node as the dashboard view). */
        const val PERMISSION: String = "helix.panel.access"

        /** Addon-storage key of the applied preset version. */
        const val VERSION_KEY: String = "panel-presets-version"

        /** Current preset version (bump to give groups without dashboard nodes the presets again). */
        const val VERSION: Int = 1

        /** Team nodes besides `helix.panel.*` that the matrix may grant. */
        val EXTRAS: List<AccessExtra> = listOf(
            AccessExtra(
                "feedback.staff",
                "Bugs bearbeiten (Team)",
                "Alle Bug-Meldungen sehen und bearbeiten, auch Exploits; ohne dieses Recht zählt man im Dashboard als Spieler.",
            ),
        )

        /** Nodes the matrix knows about besides arbitrary `helix.panel.*` nodes (used to expand partial wildcards). */
        private val KNOWN_NODES: List<String> = listOf(
            "helix.panel.login", "helix.panel.overview", "helix.panel.tasks", "helix.panel.tasks.edit",
            "helix.panel.services", "helix.panel.services.logs", "helix.panel.services.console",
            "helix.panel.services.control", "helix.panel.services.backups", "helix.panel.players",
            "helix.panel.players.manage", "helix.panel.proxy", "helix.panel.proxy.control", "helix.panel.events",
            "helix.panel.logs", "helix.panel.audit", "helix.panel.addons", "helix.panel.addons.manage",
            "helix.panel.schedules", "helix.panel.schedules.edit", "helix.panel.backups", "helix.panel.backups.restore",
            "helix.panel.files", "helix.panel.files.edit", "helix.panel.translations", "helix.panel.translations.edit",
            "helix.panel.settings", "helix.panel.access",
        ) + EXTRAS.map { it.node }

        private val SUPPORT = listOf(
            "helix.panel.login", "helix.panel.overview", "helix.panel.players", "helix.panel.events",
            // feedback.report too: the team tab Bugs calls the player-facing actions (get, comment, attach) as well.
            "helix.panel.addon.feedback", "feedback.staff", "feedback.report",
        )
        private val MOD = SUPPORT + listOf(
            "helix.panel.players.manage", "helix.panel.audit", "helix.panel.addon.bans", "helix.panel.addon.guard",
        )
        private val ADMIN = MOD + listOf(
            "helix.panel.services", "helix.panel.services.logs", "helix.panel.services.control",
            "helix.panel.proxy", "helix.panel.proxy.control", "helix.panel.tasks", "helix.panel.schedules",
            "helix.panel.backups", "helix.panel.translations", "helix.panel.translations.edit", "helix.panel.addons",
            "helix.panel.addon.clan", "helix.panel.addon.economy", "helix.panel.addon.lobby", "helix.panel.addon.motd",
            "helix.panel.addon.tablist", "helix.panel.addon.scoreboard", "helix.panel.addon.chat",
            "helix.panel.addon.profile", "helix.panel.addon.mineclash", "helix.panel.addon.cinematic",
            "helix.panel.addon.labymod",
        )
        private val DEVELOPER = ADMIN + listOf(
            "helix.panel.services.console", "helix.panel.services.backups", "helix.panel.files", "helix.panel.files.edit",
            "helix.panel.tasks.edit", "helix.panel.addons.manage", "helix.panel.logs", "helix.panel.schedules.edit",
            "helix.panel.backups.restore", "helix.panel.addon.guardlab", "helix.panel.addon.discord",
        )

        /** Tester panel nodes: sign in, the command guide and bug reporting – nothing of the team dashboard. */
        val TESTER: List<String> = listOf("helix.panel.login", "helix.panel.addon.testguide", "helix.panel.addon.bugreport")

        /** Group name to preset nodes (owner and manager keep `*`). */
        val PRESETS: Map<String, List<String>> = linkedMapOf(
            "support" to SUPPORT,
            "mod" to MOD,
            "admin" to ADMIN,
            "developer" to DEVELOPER,
            TesterAccess.GROUP to TESTER,
        )

        /**
         * Whether the matrix may change [node]: dashboard nodes and [EXTRAS], never `*` or `helix.admin`.
         *
         * @param node permission node.
         * @return `true` when the matrix may grant or revoke it.
         */
        fun isMatrixNode(node: String): Boolean =
            (node.startsWith(PANEL_PREFIX) && !node.contains('*') && node.length > PANEL_PREFIX.length) ||
                EXTRAS.any { it.node == node }

        /**
         * Whether a permission list grants everything the matrix controls.
         *
         * @param permissions a group's permissions including inherited ones.
         * @return `true` for `*`, `helix.*`, `helix.panel.*` or `helix.admin`.
         */
        fun isWildcardGroup(permissions: List<String>): Boolean =
            permissions.any { it == "*" || it == "helix.*" || it == "helix.panel.*" || it == "helix.admin" }
    }
}
