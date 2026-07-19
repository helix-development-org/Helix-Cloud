package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * A permission question, asked by bridges or other components.
 *
 * @property name player name.
 * @property permission permission node, for example `helix.maintenance.bypass`.
 * @property uuid player uuid, if known.
 */
@Serializable
data class PermissionCheckRequest(
    val name: String,
    val permission: String,
    val uuid: String? = null,
)

/**
 * Aggregated verdict of all registered permission resolvers.
 *
 * @property allowed whether any resolver granted the permission.
 */
@Serializable
data class PermissionDecision(
    val allowed: Boolean,
)
