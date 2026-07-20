package org.helix.api.proxy

import kotlinx.serialization.Serializable

/**
 * Aggregated verdict of all registered permission resolvers.
 *
 * @property allowed whether any resolver granted the permission.
 */
@Serializable
data class PermissionDecision(
    val allowed: Boolean,
)
