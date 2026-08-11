package org.helix.addons.translations

import org.helix.addon.sdk.AddonBase

/**
 * Node-side container for the in-game translations editor.
 *
 * The editor itself is a Paper-only GUI (`/translationsmenu`, shipped as this
 * HXA's `paper.jar`) plus a resource pack (`pack.zip`, the dirt background and
 * preview fonts). The actual reading and writing of network translations goes
 * through the node's built-in, `helix.admin`-gated `helix.translations.*`
 * actions (see `org.helix.node.actions.TranslationActions`) — an addon cannot
 * reach the node's `MessageRegistry` directly, so those live in the core. This
 * node component therefore only needs to exist to carry the Paper plugin and
 * pack into the network, and registers nothing itself.
 */
class TranslationsAddon : AddonBase() {
    override fun enable() {
        // Intentionally empty: all behaviour is the Paper GUI plus the core
        // helix.translations.* actions; nothing is registered node-side here.
    }
}
