package org.helix.api.addon

/**
 * Lifecycle state of a loaded addon.
 */
enum class AddonState {
    /** Addon is loaded and its actions are registered. */
    ENABLED,

    /** Addon is loaded but currently disabled. */
    DISABLED,

    /** Addon failed to load or enable. */
    FAILED,
}
