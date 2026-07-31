package org.helix.addons.discord

/**
 * Confirmation tier of an action triggered from Discord.
 */
enum class ActionTier {
    /** Read-only — executes immediately. */
    NORMAL,

    /** State-changing — requires the red confirm button (second click). */
    DESTRUCTIVE,

    /** High-impact — requires typing the target into a confirm modal. */
    CRITICAL,
}

/**
 * Classifies actions into confirmation tiers.
 *
 * Explicit configuration wins ([DiscordConfig.criticalActions] before
 * [DiscordConfig.destructiveActions] before [DiscordConfig.normalActions]);
 * unlisted actions fall back to a name heuristic: obvious read-only
 * suffixes are [ActionTier.NORMAL], everything unknown defaults to
 * [ActionTier.DESTRUCTIVE] — an action browser over the full registry must
 * never execute an unknown, possibly state-changing action without the
 * second click.
 */
object ActionTiers {
    private val READ_ONLY_SUFFIXES = listOf(
        ".list", ".info", ".get", ".check", ".status", ".overview",
        ".history", ".current", ".logs", ".catalog", ".export",
    )

    /**
     * The tier an action executes under.
     *
     * @param action the action name.
     * @param config current configuration with the explicit tier lists.
     * @return the effective tier.
     */
    fun classify(action: String, config: DiscordConfig): ActionTier = when {
        action in config.criticalActions -> ActionTier.CRITICAL
        action in config.destructiveActions -> ActionTier.DESTRUCTIVE
        action in config.normalActions -> ActionTier.NORMAL
        READ_ONLY_SUFFIXES.any { action.endsWith(it) } -> ActionTier.NORMAL
        else -> ActionTier.DESTRUCTIVE
    }
}
