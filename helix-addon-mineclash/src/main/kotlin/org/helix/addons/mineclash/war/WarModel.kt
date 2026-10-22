package org.helix.addons.mineclash.war

import kotlinx.serialization.Serializable

/** Node-side states of a clan war (contract §4). */
object WarState {
    /** Accepted, waiting for a war server with a ready spare world. */
    const val QUEUED: String = "QUEUED"

    /** Handed to a war server, which has not confirmed it yet. */
    const val ASSIGNED: String = "ASSIGNED"

    /** The war server reported the preparation phase. */
    const val PREPARE: String = "PREPARE"

    /** The war is on. */
    const val RUNNING: String = "RUNNING"

    /** Decided; result stored and paid. */
    const val FINISHED: String = "FINISHED"

    /** Ended without a decision. */
    const val CANCELLED: String = "CANCELLED"

    /** States of wars that still occupy their clans. */
    val ACTIVE: Set<String> = setOf(QUEUED, ASSIGNED, PREPARE, RUNNING)

    /** States of wars a war server hosts (or is about to). */
    val HOSTED: Set<String> = setOf(ASSIGNED, PREPARE, RUNNING)
}

/**
 * One side of a stored war.
 *
 * @property id team id (`A` = challenger, `B` = challenged clan).
 * @property clanId clan id.
 * @property name clan name at acceptance.
 * @property tag clan tag at acceptance.
 * @property members rostered members (everyone who joined, at most `maxPlayersPerClan`).
 */
@Serializable
data class WarTeamRecord(val id: String, val clanId: String, val name: String, val tag: String, val members: List<WarMember> = emptyList()) {
    /**
     * Whether a player is on the roster (by uuid or name).
     *
     * @param uuid player uuid, may be blank.
     * @param name player name (any case).
     * @return true when rostered.
     */
    fun rostered(uuid: String, name: String): Boolean =
        members.any { (uuid.isNotBlank() && it.uuid == uuid) || it.name.equals(name, ignoreCase = true) }

    /**
     * The team as wire spec.
     *
     * @return the [WarTeamSpec].
     */
    fun spec(): WarTeamSpec = WarTeamSpec(id, clanId, name, tag, members)
}

/**
 * A clan war as stored on the node (`mineclash.wars`, body JSON).
 *
 * @property warId `w-<yyyyMMdd>-<nnnn>`.
 * @property state [WarState].
 * @property teams team A (challenger) and team B (challenged clan).
 * @property serviceId hosting war server, null while queued.
 * @property bootId boot id of the hosting war server at assignment.
 * @property createdAt epoch millis of the challenge.
 * @property acceptedAt epoch millis of the acceptance.
 * @property assignedAt epoch millis of the last assignment.
 * @property prepareEndsAt epoch millis the preparation ends (reported).
 * @property runningAt epoch millis the war started.
 * @property endedAt epoch millis the war ended or was cancelled.
 * @property winner winning team id, null for a draw or no decision.
 * @property reason [WarProtocol.EndReason] of a finished war.
 * @property points final points per team id.
 * @property participants participants as reported by the war server.
 * @property cancelReason [WarProtocol.CancelReason] of a cancelled war.
 */
@Serializable
data class WarRecord(
    val warId: String,
    val state: String,
    val teams: List<WarTeamRecord>,
    val serviceId: String? = null,
    val bootId: String? = null,
    val createdAt: Long,
    val acceptedAt: Long,
    val assignedAt: Long? = null,
    val prepareEndsAt: Long? = null,
    val runningAt: Long? = null,
    val endedAt: Long? = null,
    val winner: String? = null,
    val reason: String? = null,
    val points: Map<String, Int> = emptyMap(),
    val participants: List<WarParticipant> = emptyList(),
    val cancelReason: String? = null,
) {
    /**
     * The team of a clan.
     *
     * @param clanId clan id.
     * @return the team or null when the clan is not part of this war.
     */
    fun teamOf(clanId: String): WarTeamRecord? = teams.firstOrNull { it.clanId == clanId }

    /**
     * The opposing team of a clan.
     *
     * @param clanId clan id.
     * @return the other team or null when the clan is not part of this war.
     */
    fun otherTeam(clanId: String): WarTeamRecord? = if (teamOf(clanId) == null) null else teams.firstOrNull { it.clanId != clanId }

    /**
     * A copy with one team replaced.
     *
     * @param team the new team (matched by id).
     * @return the changed record.
     */
    fun withTeam(team: WarTeamRecord): WarRecord = copy(teams = teams.map { if (it.id == team.id) team else it })
}

/**
 * A clan referenced by a challenge.
 *
 * @property id clan id.
 * @property name clan name.
 * @property tag clan tag.
 */
data class ClanRef(val id: String, val name: String, val tag: String)

/**
 * An open challenge (in memory only, contract §4).
 *
 * @property from challenging clan.
 * @property to challenged clan.
 * @property by player who challenged.
 * @property createdAt epoch millis of the challenge.
 * @property expiresAt epoch millis the challenge expires.
 */
data class Challenge(val from: ClanRef, val to: ClanRef, val by: String, val createdAt: Long, val expiresAt: Long)

/**
 * The clan-war settings, read from the MineClash config on every use (panel edits apply live).
 *
 * @property prepareSeconds preparation phase length.
 * @property maxPlayersPerClan roster limit per team.
 * @property abandonMinutes absence limit of the abandon rule.
 * @property challengeExpirySeconds validity of a challenge.
 * @property queueTimeoutMinutes longest wait for a war world.
 * @property serverLostSeconds silence after which a war server counts as lost.
 * @property warTask task hosting clan wars.
 * @property gameTask task hosting villages (players return there).
 */
data class WarSettings(
    val prepareSeconds: Int = 120,
    val maxPlayersPerClan: Int = 10,
    val abandonMinutes: Int = 15,
    val challengeExpirySeconds: Int = 300,
    val queueTimeoutMinutes: Int = 10,
    val serverLostSeconds: Int = 60,
    val warTask: String = "MineClash-War",
    val gameTask: String = "MineClash-Game",
)

/**
 * A localized message: language key, plain parameters and parameters that are keys themselves (resolved in the
 * recipient's language, for example a cancel reason).
 *
 * @property key language key.
 * @property params plain placeholder values.
 * @property refs placeholder name to language key.
 */
data class Msg(val key: String, val params: Map<String, String> = emptyMap(), val refs: Map<String, String> = emptyMap())

/** A side effect of the coordinator, run outside its lock by [WarEffectRunner]. */
sealed interface WarEffect {
    /**
     * Chat message to a player anywhere on the network (`player.message`).
     *
     * @property player recipient name.
     * @property msg the message.
     */
    data class Message(val player: String, val msg: Msg) : WarEffect

    /**
     * Moves a player to a war server (`player.connect`); a failure is reported to the player.
     *
     * @property player player name.
     * @property serviceId target service.
     */
    data class Connect(val player: String, val serviceId: String) : WarEffect

    /**
     * Starts a war server (`service.start <task>`, blocks while the workspace is prepared).
     *
     * @property task task name.
     */
    data class StartService(val task: String) : WarEffect

    /**
     * Pays a finished war (`KronenRewards.payWar`) and tells every paid player.
     *
     * @property warId war id.
     * @property players uuid to team id.
     * @property names uuid to player name (for the reward message).
     * @property winner winning team id, null for a draw.
     */
    data class PayWar(val warId: String, val players: Map<String, String>, val names: Map<String, String>, val winner: String?) : WarEffect
}

/**
 * What a coordinator call produced: lines for the caller and side effects.
 *
 * @property replies messages for the calling player.
 * @property effects side effects to run.
 */
data class Outcome(val replies: List<Msg> = emptyList(), val effects: List<WarEffect> = emptyList())

/**
 * Decision for a player logging in to a war server.
 *
 * @property warId war the player belongs to, null when refused.
 * @property team team id, null when refused.
 * @property refusal refusal message, null when admitted.
 */
data class ArrivalDecision(val warId: String? = null, val team: String? = null, val refusal: Msg? = null)
