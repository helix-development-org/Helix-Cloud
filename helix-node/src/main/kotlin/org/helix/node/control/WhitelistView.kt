package org.helix.node.control

import kotlinx.serialization.Serializable

/**
 * Dashboard view of the network whitelist.
 *
 * @property enabled whether enforcement is active.
 * @property entries allow-listed account names.
 */
@Serializable
data class WhitelistView(val enabled: Boolean, val entries: List<String>)

/**
 * Request body to toggle the whitelist.
 *
 * @property enabled desired enforcement state.
 */
@Serializable
data class WhitelistToggleRequest(val enabled: Boolean)

/**
 * Request body to add or remove a whitelist entry.
 *
 * @property player account name.
 */
@Serializable
data class WhitelistEntryRequest(val player: String)
