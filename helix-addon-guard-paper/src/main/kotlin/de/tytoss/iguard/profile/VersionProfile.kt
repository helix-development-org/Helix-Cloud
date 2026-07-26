package de.tytoss.iguard.profile

import com.github.retrooper.packetevents.protocol.player.ClientVersion

/** Client-version movement physics constants the checks compare observed motion against. */
data class VersionProfile(
    val gravity: Double,
    val drag: Double,
    val jumpVelocity: Double,
    val baseGroundSpeed: Double,
    val baseAirSpeed: Double,
    val sprintMultiplier: Double,
    val reach: Double
)

/** Lookup of [VersionProfile]s per supported client version range. */
object VersionProfiles {
    private val v1 = VersionProfile(0.08, 0.98, 0.42, 0.29, 0.36, 1.3, 3.0)

    /** The profile for [version], or null for unsupported (too old / too new) clients. */
    fun forClient(version: ClientVersion): VersionProfile? {
        if (version.isOlderThan(ClientVersion.V_1_21)) return null
        if (version.isNewerThan(ClientVersion.V_1_21_11)) return null
        return v1
    }
}
