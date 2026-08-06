package org.helix.addons.discord

/**
 * Outcome of a Discord-side permission check.
 */
sealed class Access {
    /**
     * The Discord user may proceed.
     *
     * @property link the link the decision is based on.
     * @property actorName the current Minecraft name acting on behalf.
     */
    data class Granted(val link: DiscordLink, val actorName: String) : Access()

    /** The Discord user has no linked Minecraft account. */
    data object NotLinked : Access()

    /**
     * The linked account lacks a required permission node.
     *
     * @property link the link the decision is based on.
     * @property node the missing node.
     */
    data class Denied(val link: DiscordLink, val node: String) : Access()
}

/**
 * Decides what a Discord user may do, exclusively through their linked
 * Minecraft account's Helix permissions — there is no user-id bypass and
 * no allowlist.
 *
 * Every action carries its own node `helix.discord.action.<action>`
 * (grantable in groups through the permissions addon's wildcards, for
 * example `helix.discord.action.service.*`). When the action descriptor
 * additionally declares its own permission (the in-game command node),
 * both must be granted.
 *
 * @property links confirmed account links.
 * @property hasPermission permission oracle of the node, checked by player
 *   name.
 * @property currentName resolves a uuid to its current name so renames do
 *   not detach permissions from a link; falls back to the name recorded at
 *   link time.
 */
class PermissionGate(
    private val links: LinkStore,
    private val hasPermission: (player: String, node: String) -> Boolean,
    private val currentName: (uuid: String) -> String? = { null },
) {
    /**
     * Checks whether a Discord user may run an action.
     *
     * @param discordId the Discord user id.
     * @param action the action name.
     * @param actionPermission the descriptor's own permission node, checked
     *   in addition when present.
     * @return the access decision.
     */
    fun forAction(discordId: String, action: String, actionPermission: String? = null): Access {
        val link = links.byDiscord(discordId) ?: return Access.NotLinked
        val actor = currentName(link.uuid) ?: link.playerName
        val node = actionNode(action)
        if (!hasPermission(actor, node)) {
            return Access.Denied(link, node)
        }
        if (actionPermission != null && !hasPermission(actor, actionPermission)) {
            return Access.Denied(link, actionPermission)
        }
        return Access.Granted(link, actor)
    }

    /**
     * Checks whether a Discord user holds one of the addon's own feature
     * nodes, for example [SETUP_NODE].
     *
     * @param discordId the Discord user id.
     * @param node the feature node.
     * @return the access decision.
     */
    fun forNode(discordId: String, node: String): Access {
        val link = links.byDiscord(discordId) ?: return Access.NotLinked
        val actor = currentName(link.uuid) ?: link.playerName
        return if (hasPermission(actor, node)) Access.Granted(link, actor) else Access.Denied(link, node)
    }

    companion object {
        /** Prefix of the per-action nodes. */
        const val ACTION_NODE_PREFIX = "helix.discord.action."

        /** Node required to link an account (in-game and Discord side). */
        const val LINK_NODE = "helix.discord.link"

        /** Node required for `/helix setup` and posting the panels. */
        const val SETUP_NODE = "helix.discord.setup"

        /** Node required for the profile context-menu command. */
        const val WHOIS_NODE = "helix.discord.whois"

        /**
         * The per-action node of an action.
         *
         * @param action the action name.
         * @return `helix.discord.action.<action>`.
         */
        fun actionNode(action: String): String = ACTION_NODE_PREFIX + action
    }
}
