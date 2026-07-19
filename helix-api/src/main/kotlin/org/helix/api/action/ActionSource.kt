package org.helix.api.action

/**
 * Origin of an action invocation.
 */
enum class ActionSource {
    /** Invoked from the interactive node CLI. */
    CLI,

    /** Invoked through the control REST API. */
    REST,

    /** Invoked by a platform bridge. */
    BRIDGE,

    /** Invoked by an addon. */
    ADDON,

    /** Invoked by the node itself, for example the auto-scaler. */
    SYSTEM,
}
