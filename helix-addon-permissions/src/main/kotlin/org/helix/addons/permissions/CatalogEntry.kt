package org.helix.addons.permissions

import kotlinx.serialization.Serializable

/**
 * One known permission node in the network-wide catalog.
 *
 * @property node the permission node, for example `helix.mod.kick`.
 * @property source where the node was discovered: `core`, `addon:<id>` or
 *  `plugin:<name>`.
 */
@Serializable
data class CatalogEntry(val node: String, val source: String)
