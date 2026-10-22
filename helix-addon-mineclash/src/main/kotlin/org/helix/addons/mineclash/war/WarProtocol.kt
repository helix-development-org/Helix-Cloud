package org.helix.addons.mineclash.war

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire format between the node's clan-war coordinator and the MineClash war servers (Paper, task
 * `MineClash-War`). The Paper side mirrors these shapes in `de.tytoss.mineclash.network.WarProtocol`;
 * both sides test against the same fixtures (`src/test/resources/war-fixtures/`). Contract:
 * `Mineclash/docs/CLANWAR-CONTRACT.md`.
 */
object WarProtocol {
    /** War phases a war server reports for a hosted war. */
    object Phase {
        /** World ready, members join; no PvP, no points, bases protected. */
        const val PREPARE: String = "PREPARE"

        /** The war is on. */
        const val RUNNING: String = "RUNNING"

        /** Decided; players are being sent back, the world is torn down. */
        const val FINISHED: String = "FINISHED"
    }

    /** Why a war ended (`WarResult.reason`). */
    object EndReason {
        /** A clan reached `war.pointsWin`. */
        const val POINT_LIMIT: String = "POINT_LIMIT"

        /** A base was destroyed and its team has no defenders left. */
        const val BASE_DESTROYED: String = "BASE_DESTROYED"

        /** One clan was absent for `war.abandonMinutes` while the other was present. */
        const val ABANDONED: String = "ABANDONED"

        /** Both clans were absent for `war.abandonMinutes`; decided by points. */
        const val BOTH_ABSENT: String = "BOTH_ABSENT"

        /** Ended without a decision (cancelled by the node, an admin or a clan). */
        const val CANCELLED: String = "CANCELLED"
    }

    /** Why the node cancels a war (`WarCancel.reason`). */
    object CancelReason {
        /** A clan of the war was disbanded. */
        const val DISBANDED: String = "DISBANDED"

        /** The hosting war server restarted or stopped polling. */
        const val SERVER_LOST: String = "SERVER_LOST"

        /** No war world became free within `war.queueTimeoutMinutes`. */
        const val QUEUE_TIMEOUT: String = "QUEUE_TIMEOUT"

        /** Cancelled by staff (`mineclash.war.cancel`). */
        const val ADMIN: String = "ADMIN"

        /** Cancelled by an owner/officer before the war started (`/clanwar cancel`). */
        const val CLAN: String = "CLAN"
    }

    /** Team ids: the challenging clan is `A`, the challenged clan `B`. */
    val TEAMS: List<String> = listOf("A", "B")

    /** JSON settings of the protocol (unknown keys ignored, defaults written). */
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /**
     * Parses a war server status (`mineclash.war.poll` argument).
     *
     * @param raw the JSON text.
     * @return the status.
     */
    fun status(raw: String): WarServerStatus = json.decodeFromString(WarServerStatus.serializer(), raw)

    /**
     * Parses a war result (`mineclash.war.finish` argument).
     *
     * @param raw the JSON text.
     * @return the result.
     */
    fun result(raw: String): WarResult = json.decodeFromString(WarResult.serializer(), raw)

    /**
     * Encodes the desired state answered to a poll.
     *
     * @param state the desired state.
     * @return the JSON text.
     */
    fun encode(state: WarDesiredState): String = json.encodeToString(WarDesiredState.serializer(), state)

    /**
     * Encodes an arrival decision (`mineclash.war.arrive` answer).
     *
     * @param arrival the decision.
     * @return the JSON text.
     */
    fun encode(arrival: WarArrival): String = json.encodeToString(WarArrival.serializer(), arrival)
}

/**
 * Pool state of a war server.
 *
 * @property ready fully pregenerated spare world sets.
 * @property target spare sets the server aims for (`war.poolSize`).
 * @property generating whether a set is being generated right now.
 * @property progress progress of the set in generation (0..1).
 */
@Serializable
data class PoolStatus(val ready: Int = 0, val target: Int = 0, val generating: Boolean = false, val progress: Double = 0.0)

/**
 * One war a war server hosts.
 *
 * @property warId war id.
 * @property phase [WarProtocol.Phase].
 * @property phaseEndsAt epoch millis the current phase ends (PREPARE), null when open-ended.
 * @property points points per team id (`A`, `B`).
 * @property present players per team id currently in the war's worlds.
 */
@Serializable
data class HostedWar(
    val warId: String,
    val phase: String,
    val phaseEndsAt: Long? = null,
    val points: Map<String, Int> = emptyMap(),
    val present: Map<String, Int> = emptyMap(),
)

/**
 * Status a war server sends with every poll (`mineclash.war.poll <serviceId> <json>`).
 *
 * @property bootId random id per plugin start; a new id means every war of the previous boot is lost.
 * @property capacity wars the server can host at once (config and heap).
 * @property pool spare world state.
 * @property mspt average milliseconds per tick.
 * @property wars hosted wars.
 * @property rejected wars of the desired state the server could not start (no ready world).
 */
@Serializable
data class WarServerStatus(
    val bootId: String,
    val capacity: Int = 1,
    val pool: PoolStatus = PoolStatus(),
    val mspt: Double = 0.0,
    val wars: List<HostedWar> = emptyList(),
    val rejected: List<String> = emptyList(),
)

/**
 * A rostered member of a war team.
 *
 * @property uuid player UUID.
 * @property name player name.
 */
@Serializable
data class WarMember(val uuid: String, val name: String)

/**
 * One side of a war.
 *
 * @property id team id (`A` = challenger, `B` = challenged clan).
 * @property clanId clan id (lower-case clan name).
 * @property name clan name.
 * @property tag clan tag.
 * @property members rostered members (those who joined; at most `maxPlayersPerClan`).
 */
@Serializable
data class WarTeamSpec(val id: String, val clanId: String, val name: String, val tag: String, val members: List<WarMember> = emptyList())

/**
 * A war the node wants a war server to host.
 *
 * @property warId war id.
 * @property prepareSeconds length of the preparation phase.
 * @property maxPlayersPerClan roster limit per team.
 * @property abandonMinutes absence limit of the abandon rule.
 * @property teams the two teams, `A` first.
 */
@Serializable
data class WarSpec(
    val warId: String,
    val prepareSeconds: Int,
    val maxPlayersPerClan: Int,
    val abandonMinutes: Int,
    val teams: List<WarTeamSpec>,
)

/**
 * A war the war server must end without a result.
 *
 * @property warId war id.
 * @property reason [WarProtocol.CancelReason].
 */
@Serializable
data class WarCancel(val warId: String, val reason: String)

/**
 * Complete desired state answered to every poll (a lost answer loses nothing).
 *
 * @property returnTo service players are sent back to after a war (a MineClash-Game service).
 * @property wars every war this server should host, with the current roster.
 * @property cancel wars to end without a result.
 */
@Serializable
data class WarDesiredState(val returnTo: String, val wars: List<WarSpec> = emptyList(), val cancel: List<WarCancel> = emptyList())

/**
 * Decision for a player logging in to a war server (`mineclash.war.arrive`).
 *
 * @property ok whether the player may join.
 * @property warId the war the player belongs to.
 * @property team the player's team id.
 * @property reason refusal text (MiniMessage, already localized), when not [ok].
 */
@Serializable
data class WarArrival(val ok: Boolean, val warId: String? = null, val team: String? = null, val reason: String? = null)

/**
 * A participant in a war result.
 *
 * @property uuid player UUID.
 * @property name player name.
 * @property team team id.
 * @property kills player kills.
 * @property deaths deaths.
 * @property presentAtEnd whether the player was on the war server when the war ended (gets the war chest).
 */
@Serializable
data class WarParticipant(
    val uuid: String,
    val name: String,
    val team: String,
    val kills: Int = 0,
    val deaths: Int = 0,
    val presentAtEnd: Boolean = false,
)

/**
 * Result a war server reports once per war (`mineclash.war.finish <serviceId> <json>`, retried until recorded).
 *
 * @property warId war id.
 * @property winner winning team id, null for a draw or a cancelled war.
 * @property reason [WarProtocol.EndReason].
 * @property points final points per team id.
 * @property runningAt epoch millis the war entered RUNNING, null when it never did.
 * @property endedAt epoch millis the war ended.
 * @property participants every rostered player.
 */
@Serializable
data class WarResult(
    val warId: String,
    val winner: String? = null,
    val reason: String,
    val points: Map<String, Int> = emptyMap(),
    val runningAt: Long? = null,
    val endedAt: Long,
    val participants: List<WarParticipant> = emptyList(),
)
