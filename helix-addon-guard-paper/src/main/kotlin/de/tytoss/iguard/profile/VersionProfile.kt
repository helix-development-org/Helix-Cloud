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
    val reach: Double,
)

/**
 * Lookup of [VersionProfile]s per supported client version range.
 *
 * The caller (`CheckEngine`'s per-frame processing) already treats a `null` profile as a
 * deliberate, safe "do not run movement/combat physics checks for this client" — it still runs the
 * version-independent deterministic/protocol/world checks, it just skips the ones whose thresholds are
 * calibrated against a specific physics model. That makes `null` a legitimate outcome here, not a gap
 * to eliminate: a version this object is not confident about should stay `null` rather than get a
 * guessed profile that then either false-positives real players or blind-spots real cheats.
 */
object VersionProfiles {
    /**
     * Vanilla base movement physics: per-tick gravity, air drag, jump velocity, base walk/sprint speed
     * and vanilla attack reach. These have been stable since Minecraft 1.9 (the combat update that
     * introduced the attack-cooldown mechanic every combat check here is calibrated against, and which
     * also settled sprint-jump and general movement to their still-current values) through 1.21.x —
     * see the Minecraft Wiki's "Entity" (gravity 0.08 blocks/tick², 0.98 air drag while airborne) and
     * "Player"/"Sprinting" pages, cross-checked against vanilla server source. Applies to every
     * supported protocol from 1.9 up to 1.21.11.
     */
    private val modern = VersionProfile(0.08, 0.98, 0.42, 0.29, 0.36, 1.3, 3.0)

    /**
     * The profile for [version], or `null` for a client this build is not confident checking.
     *
     * Deliberately excludes pre-1.9 clients (1.8 and older, still reachable through ViaVersion):
     * Minecraft 1.9 replaced instant, uncapped melee hits with the attack-cooldown mechanic every
     * combat check here (autoclicker, rotation, reach) is calibrated against. A legitimate 1.8 client's
     * "no cooldown" click pattern would read as an obvious autoclicker/rotation-snap violation under
     * those checks, and deliberately softening them for one version family would just as surely open a
     * blind spot for real cheats on every other (1.9+) client. Base movement physics (gravity/drag/
     * speed) have not changed since 1.8 either, so it is specifically the shared movement+combat
     * profile shape — one [VersionProfile] backs both — that makes a confident pre-1.9 entry too risky
     * to add without splitting movement from combat calibration, which is a larger change than this
     * fix's scope. Unsupported clients are skipped entirely (see the class doc) rather than guessed at.
     *
     * @param version reported client protocol version.
     * @return the physics profile, or `null` for clients outside 1.9–1.21.11.
     */
    fun forClient(version: ClientVersion): VersionProfile? {
        if (version.isOlderThan(ClientVersion.V_1_9)) return null
        if (version.isNewerThan(ClientVersion.V_1_21_11)) return null
        return modern
    }
}
