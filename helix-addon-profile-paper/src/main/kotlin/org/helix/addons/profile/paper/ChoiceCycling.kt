package org.helix.addons.profile.paper

import org.helix.api.addon.ProfileSettingOption

/**
 * Pure cycling logic for a [org.helix.api.addon.ProfileSettingType.Choice]
 * item click: picks the next unlocked option after the current value,
 * wrapping around and skipping locked options entirely.
 */
object ChoiceCycling {
    /**
     * The next unlocked option to select after [current].
     *
     * @param options every declared option (locked ones included, so they
     *  can still be shown greyed out).
     * @param current the currently chosen option id.
     * @return the next unlocked option, or `null` when none are unlocked.
     */
    fun next(options: List<ProfileSettingOption>, current: String): ProfileSettingOption? {
        val unlocked = options.filter { it.unlocked }
        if (unlocked.isEmpty()) {
            return null
        }
        val currentIndex = unlocked.indexOfFirst { it.id == current }
        return unlocked[(currentIndex + 1).mod(unlocked.size)]
    }
}
