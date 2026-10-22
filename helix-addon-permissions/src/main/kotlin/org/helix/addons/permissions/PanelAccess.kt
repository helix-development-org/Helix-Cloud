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
 * Changing the matrix is reserved for full admins ([EDIT_PERMISSION]): several dashboard nodes are admin-equivalent
 * ([CRITICAL]), so whoever may hand them out could make themselves an admin. Holders of `helix.panel.access` may only
 * look at it.
 *
 * [presetsOnce] gives each standard team group and the testers a starting set exactly once (one marker per group),
 * and only when the group has no dashboard node yet – a negated `-helix.panel.*` node counts too –, so a choice made
 * in the matrix is never overwritten or re-granted. Presets never contain [CRITICAL] nodes.
 *
 * @property store the permission store.
 * @property storage addon storage holding the preset markers.
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
        // Team members working on bugs also use the player-facing feedback actions (get, comment, attach), which
        // require feedback.report: granting the team node brings the reporting node along.
        val added = if (node == FEEDBACK_STAFF) listOf(node, FEEDBACK_REPORT) else listOf(node)
        val permissions = if (grant) (group.permissions + added).distinct() else group.permissions - node
        if (permissions != group.permissions) store.saveGroup(group.copy(permissions = permissions))
        return null
    }

    /**
     * Applies [PRESETS] to every preset group that was never handled before (marker per group under
     * [HANDLED_KEY]). A handled group is never touched again, whatever happens to its nodes later.
     *
     * @return names of the groups that received their preset.
     */
    fun presetsOnce(): List<String> {
        val handled = handledGroups().toMutableSet()
        // Installations that ran the first preset version (one global marker) count every preset group as handled.
        if (storage.read(VERSION_KEY) != null) handled += PRESETS.keys
        val changed = mutableListOf<String>()
        PRESETS.forEach { (name, nodes) ->
            if (name in handled) return@forEach
            val group = store.group(name) ?: return@forEach
            if (give(group, nodes)) changed += group.name
            handled += name
        }
        storage.write(HANDLED_KEY, handled.sorted().joinToString("\n"))
        return changed
    }

    /**
     * Gives the named preset groups (or, without names, every preset group never handled before) their preset when
     * they have no dashboard node at all (additive, keeps all other permissions). Wildcard groups are left alone.
     *
     * @param names groups to apply to; empty = only groups that were never handled.
     * @return names of the changed groups.
     */
    fun applyPresets(names: List<String> = emptyList()): List<String> {
        val handled = handledGroups().toMutableSet()
        val targets = if (names.isEmpty()) PRESETS.keys.filter { it !in handled } else names.map { it.lowercase() }
        val changed = mutableListOf<String>()
        targets.forEach { name ->
            val nodes = PRESETS[name] ?: return@forEach
            val group = store.group(name) ?: return@forEach
            if (give(group, nodes)) changed += group.name
            handled += name
        }
        storage.write(HANDLED_KEY, handled.sorted().joinToString("\n"))
        return changed
    }

    private fun give(group: PermissionGroup, nodes: List<String>): Boolean {
        if (isWildcardGroup(group.permissions) || isConfigured(group.permissions)) return false
        store.saveGroup(group.copy(permissions = (group.permissions + nodes).distinct()))
        return true
    }

    private fun handledGroups(): Set<String> =
        storage.read(HANDLED_KEY)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** Presets, matrix node rules and storage keys. */
    companion object {
        /** Prefix of every dashboard permission. */
        const val PANEL_PREFIX: String = "helix.panel."

        /** Permission of `perm.access.matrix` (read; same node as the dashboard view). */
        const val PERMISSION: String = "helix.panel.access"

        /** Permission to change the matrix and apply presets: full admin only. */
        const val EDIT_PERMISSION: String = "helix.admin"

        /** Addon-storage key of the first preset version (one global marker; read for upgrades only). */
        const val VERSION_KEY: String = "panel-presets-version"

        /** Addon-storage key of the preset groups already handled (one name per line). */
        const val HANDLED_KEY: String = "panel-presets-groups"

        /** Team node of the bug workflow. */
        const val FEEDBACK_STAFF: String = "feedback.staff"

        /** Reporting node, needed by the player-facing feedback actions the team tab uses too. */
        const val FEEDBACK_REPORT: String = "feedback.report"

        /**
         * Admin-equivalent dashboard nodes (console, file writes, task edits, addon switches, scheduled actions, the
         * matrix itself): presets never grant them, the dashboard marks them with a warning.
         */
        val CRITICAL: Set<String> = setOf(
            "helix.panel.services.console", "helix.panel.files.edit", "helix.panel.tasks.edit",
            "helix.panel.addons.manage", "helix.panel.schedules.edit", "helix.panel.access",
        )

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
        // No admin-equivalent node in any preset ([CRITICAL]): console, file edits, task edits, addon switches and
        // scheduled jobs are granted deliberately in the matrix by an admin.
        private val DEVELOPER = ADMIN + listOf(
            "helix.panel.services.backups", "helix.panel.files", "helix.panel.logs", "helix.panel.backups.restore",
            "helix.panel.addon.guardlab", "helix.panel.addon.discord",
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

        /**
         * Whether a group's dashboard access was already decided: it holds or negates at least one dashboard node.
         *
         * @param permissions the group's own permissions.
         * @return `true` when a `helix.panel.*` or `-helix.panel.*` node is present.
         */
        fun isConfigured(permissions: List<String>): Boolean =
            permissions.any { it.removePrefix("-").startsWith(PANEL_PREFIX) }
    }
}
