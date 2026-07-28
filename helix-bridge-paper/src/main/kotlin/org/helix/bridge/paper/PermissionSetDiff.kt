package org.helix.bridge.paper

/**
 * Pure diff between the permission nodes currently mirrored onto a player's
 * Bukkit [org.bukkit.permissions.PermissionAttachment] and the nodes the
 * node just resolved for them.
 *
 * Kept separate from the Bukkit-facing sync so the (un)grant decision is
 * unit-testable without a running server.
 */
object PermissionSetDiff {
    /**
     * Computes which nodes must be granted and which must be revoked to
     * turn [current] into [target].
     *
     * @param current nodes presently set `true` on the attachment.
     * @param target nodes the player should hold now.
     * @return nodes to grant, and nodes to revoke.
     */
    fun diff(current: Set<String>, target: Set<String>): PermissionDiffResult =
        PermissionDiffResult(
            toGrant = target - current,
            toRevoke = current - target,
        )
}

/**
 * Result of a [PermissionSetDiff.diff] computation.
 *
 * @property toGrant nodes to set to `true`.
 * @property toRevoke nodes to unset.
 */
data class PermissionDiffResult(
    val toGrant: Set<String>,
    val toRevoke: Set<String>,
) {
    /** Whether applying this diff would change anything. */
    fun isEmpty(): Boolean = toGrant.isEmpty() && toRevoke.isEmpty()
}
