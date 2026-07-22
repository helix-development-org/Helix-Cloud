package org.helix.addons.permissions

import kotlinx.serialization.Serializable

/**
 * A permission node or group membership granted until an expiry time.
 *
 * @property value the permission node or group name.
 * @property expiresAtEpochMs epoch millis after which the grant is ignored.
 */
@Serializable
data class TimedGrant(
    val value: String,
    val expiresAtEpochMs: Long,
) {
    /**
     * Whether the grant is still active.
     *
     * @param nowEpochMs current epoch millis.
     * @return `true` while not yet expired.
     */
    fun active(nowEpochMs: Long): Boolean = expiresAtEpochMs > nowEpochMs
}
