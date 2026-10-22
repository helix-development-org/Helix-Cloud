package org.helix.addons.mineclash.war

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.helix.addons.mineclash.clan.ClanRead
import org.helix.addons.mineclash.clan.ClanSource
import org.helix.addons.mineclash.clan.ClanView
import org.helix.api.player.OnlinePlayer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Answer to a war-server poll.
 *
 * @property state the complete desired state for the server.
 * @property effects side effects to run.
 */
data class PollAnswer(val state: WarDesiredState, val effects: List<WarEffect>)

/**
 * Answer to a reported war result.
 *
 * @property answer `recorded` or `duplicate`.
 * @property effects side effects to run.
 */
data class FinishAnswer(val answer: String, val effects: List<WarEffect>)

/**
 * The clan-war coordinator on the node (contract `CLANWAR-CONTRACT.md` §4/§5): challenges, wars and their
 * hosting war servers.
 *
 * Every public method is synchronized and pure apart from the [WarStore] and the injected clan/presence reads:
 * it returns messages and side effects ([WarEffect]) instead of calling `player.message`, `player.connect` or
 * `service.start` itself, so the blocking ones run outside the lock ([WarEffectRunner]).
 *
 * @property store war persistence.
 * @property clans clan lookups (fresh `clan.detail` for rank and membership decisions).
 * @property online players currently on the network.
 * @property uuidOf player name to uuid (last known), null when unknown.
 * @property settings the clan-war settings (read on every use).
 * @property returnTo service players return to after a war.
 * @property clock time source.
 */
class WarCoordinator(
    private val store: WarStore,
    private val clans: ClanSource,
    private val online: () -> List<OnlinePlayer>,
    private val uuidOf: (String) -> String?,
    private val settings: () -> WarSettings,
    private val returnTo: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Server(val bootId: String, val capacity: Int, val pool: PoolStatus, val lastPollAt: Long, val hosted: Set<String>)

    private class Live(var lastReportedAt: Long, var points: Map<String, Int> = emptyMap(), var present: Map<String, Int> = emptyMap()) {
        /** Team id → epoch millis since which nobody of the team is in the war world (RUNNING only). */
        val absentSince = HashMap<String, Long>()

        /** Team id → warning thresholds (minutes) already announced for the current absence. */
        val warned = HashMap<String, MutableSet<Int>>()
    }

    private class PendingCancel(val cancel: WarCancel, var polls: Int = 0)

    private val wars = LinkedHashMap<String, WarRecord>()
    private val live = HashMap<String, Live>()
    private val challenges = ArrayList<Challenge>()
    private val servers = LinkedHashMap<String, Server>()
    private val skips = HashMap<String, Long>()
    private val cancels = HashMap<String, MutableList<PendingCancel>>()

    /** `warId|clanId` → consecutive membership checks that found the clan missing (3 → DISBANDED). */
    private val missingClans = HashMap<String, Int>()
    private var lastServiceStart = Long.MIN_VALUE / 2
    private var lastMembershipCheck: Long

    init {
        val now = clock()
        store.loadActive().forEach { record ->
            wars[record.warId] = record
            live[record.warId] = Live(now)
        }
        lastMembershipCheck = now
    }

    // ------------------------------------------------------------------ player command

    /**
     * Runs `/clanwar <sub> [Tag]` (also the clan menu's `mineclash.war.command`).
     *
     * @param executor the player.
     * @param args sub command and arguments (without the executor).
     * @return replies and effects.
     */
    @Synchronized
    fun command(executor: String, args: List<String>): Outcome {
        val tag = args.getOrNull(1)
        return when (args.firstOrNull()?.lowercase()) {
            "challenge", "herausfordern" -> if (tag == null) usage() else challenge(executor, tag)
            "accept", "annehmen" -> if (tag == null) usage() else accept(executor, tag)
            "deny", "ablehnen" -> if (tag == null) usage() else deny(executor, tag)
            "cancel", "abbrechen" -> cancel(executor)
            "join", "beitreten" -> join(executor)
            "status", null -> status(executor)
            else -> usage()
        }
    }

    /**
     * `/clanwar challenge <Tag>`.
     *
     * @param executor challenging player (owner/officer).
     * @param tag tag, name or id of the challenged clan.
     * @return replies and effects.
     */
    @Synchronized
    fun challenge(executor: String, tag: String): Outcome {
        val now = clock()
        val own = ownClan(executor) ?: return reply(Msg("clanwar.no_clan"))
        if (!own.isOfficer(executor)) return reply(Msg("clanwar.no_rank"))
        val target = clans.detail(tag) ?: return reply(Msg("clanwar.unknown_clan", mapOf("clan" to tag)))
        if (target.id == own.id) return reply(Msg("clanwar.self"))
        if (activeWarOf(own.id) != null) return reply(Msg("clanwar.busy_own"))
        if (activeWarOf(target.id) != null) return reply(Msg("clanwar.busy_target", names(target)))
        // A→B open while B challenges A: that is an acceptance.
        open(now).firstOrNull { it.from.id == target.id && it.to.id == own.id }?.let { mutual ->
            return startWar(mutual, own, target, now)
        }
        if (open(now).any { it.from.id == own.id }) return reply(Msg("clanwar.busy_own"))
        val officers = onlineMembers(target) { target.isOfficer(it) }
        if (officers.isEmpty()) return reply(Msg("clanwar.no_officer_online", names(target)))
        val expiry = settings().challengeExpirySeconds.coerceAtLeast(30) * 1000L
        val challenge = Challenge(ref(own), ref(target), executor, now, now + expiry)
        challenges += challenge
        val effects = mutableListOf<WarEffect>()
        officers.forEach { officer ->
            effects += WarEffect.Message(officer, Msg("clanwar.challenge.received", names(own) + ("player" to executor)))
        }
        onlineMembers(own) { !it.equals(executor, ignoreCase = true) }.forEach { member ->
            effects += WarEffect.Message(member, Msg("clanwar.challenge.clan_info", names(target) + ("player" to executor)))
        }
        preStartService(now, effects)
        return Outcome(listOf(Msg("clanwar.challenge.sent", names(target) + ("minutes" to minutesLeft(challenge, now).toString()))), effects)
    }

    /**
     * `/clanwar accept <Tag>`.
     *
     * @param executor accepting player (owner/officer of the challenged clan).
     * @param tag tag, name or id of the challenging clan.
     * @return replies and effects.
     */
    @Synchronized
    fun accept(executor: String, tag: String): Outcome {
        val now = clock()
        val own = ownClan(executor) ?: return reply(Msg("clanwar.no_clan"))
        if (!own.isOfficer(executor)) return reply(Msg("clanwar.no_rank"))
        val challenger = clans.detail(tag) ?: return reply(Msg("clanwar.challenge.none", mapOf("clan" to tag)))
        val challenge = open(now).firstOrNull { it.from.id == challenger.id && it.to.id == own.id }
            ?: return reply(Msg("clanwar.challenge.none", mapOf("clan" to tag)))
        if (activeWarOf(own.id) != null) return reply(Msg("clanwar.busy_own"))
        if (activeWarOf(challenger.id) != null) {
            challenges.remove(challenge)
            return reply(Msg("clanwar.busy_target", names(challenger)))
        }
        return startWar(challenge, own, challenger, now)
    }

    /**
     * `/clanwar deny <Tag>`.
     *
     * @param executor denying player (owner/officer of the challenged clan).
     * @param tag tag, name or id of the challenging clan.
     * @return replies and effects.
     */
    @Synchronized
    fun deny(executor: String, tag: String): Outcome {
        val now = clock()
        val own = ownClan(executor) ?: return reply(Msg("clanwar.no_clan"))
        if (!own.isOfficer(executor)) return reply(Msg("clanwar.no_rank"))
        val challenger = clans.detail(tag)
        val challenge = challenger?.let { c -> open(now).firstOrNull { it.from.id == c.id && it.to.id == own.id } }
            ?: return reply(Msg("clanwar.challenge.none", mapOf("clan" to tag)))
        challenges.remove(challenge)
        val effects = onlineMembers(challenger).map { WarEffect.Message(it, Msg("clanwar.challenge.denied", names(own))) }
        return Outcome(listOf(Msg("clanwar.challenge.deny_done", names(challenger))), effects)
    }

    /**
     * `/clanwar cancel`: withdraws the own challenge, or cancels the own war before it started.
     *
     * @param executor owner/officer.
     * @return replies and effects.
     */
    @Synchronized
    fun cancel(executor: String): Outcome {
        val now = clock()
        val own = ownClan(executor) ?: return reply(Msg("clanwar.no_clan"))
        if (!own.isOfficer(executor)) return reply(Msg("clanwar.no_rank"))
        open(now).firstOrNull { it.from.id == own.id }?.let { challenge ->
            challenges.remove(challenge)
            val target = clans.detail(challenge.to.id)
            val effects = target?.let { view ->
                onlineMembers(view) { view.isOfficer(it) }.map { WarEffect.Message(it, Msg("clanwar.challenge.withdrawn", names(own))) }
            }.orEmpty()
            return Outcome(listOf(Msg("clanwar.challenge.cancelled", refNames(challenge.to))), effects)
        }
        val war = activeWarOf(own.id) ?: return reply(Msg("clanwar.cancel.none"))
        if (war.state == WarState.RUNNING) return reply(Msg("clanwar.cancel.running"))
        val effects = mutableListOf<WarEffect>()
        cancelWar(war, WarProtocol.CancelReason.CLAN, now, effects)
        return Outcome(emptyList(), effects)
    }

    /**
     * `/clanwar join`: rosters the player (within the limit) and moves them to the war server.
     *
     * @param executor any member of a clan at war.
     * @return replies and effects.
     */
    @Synchronized
    fun join(executor: String): Outcome {
        val now = clock()
        val clanId = clans.clanIdOf(executor) ?: return reply(Msg("clanwar.no_clan"))
        val war = activeWarOf(clanId) ?: return reply(Msg("clanwar.join.no_war"))
        if (war.state == WarState.QUEUED || war.state == WarState.ASSIGNED || war.serviceId == null) {
            return reply(Msg("clanwar.join.not_ready"))
        }
        val team = war.teamOf(clanId) ?: return reply(Msg("clanwar.join.not_member"))
        val uuid = onlineByName()[executor.lowercase()]?.uuid ?: uuidOf(executor).orEmpty()
        if (!team.rostered(uuid, executor)) {
            val view = clans.detail(clanId)
            if (view == null || !view.has(executor)) return reply(Msg("clanwar.join.not_member"))
            val max = settings().maxPlayersPerClan.coerceAtLeast(1)
            if (team.members.size >= max) return reply(Msg("clanwar.join.full", mapOf("max" to max.toString())))
            put(war.withTeam(team.copy(members = team.members + WarMember(uuid, executor))))
        }
        return Outcome(listOf(Msg("clanwar.join.connecting")), listOf(WarEffect.Connect(executor, war.serviceId)))
    }

    /**
     * `/clanwar status`.
     *
     * @param executor any player.
     * @return replies.
     */
    @Synchronized
    fun status(executor: String): Outcome {
        val now = clock()
        val own = ownClan(executor) ?: return reply(Msg("clanwar.no_clan"))
        val lines = mutableListOf(Msg("clanwar.status.header", names(own)))
        open(now).filter { it.from.id == own.id }.forEach {
            lines += Msg("clanwar.status.outgoing", refNames(it.to) + ("minutes" to minutesLeft(it, now).toString()))
        }
        open(now).filter { it.to.id == own.id }.forEach {
            lines += Msg("clanwar.status.incoming", refNames(it.from) + ("minutes" to minutesLeft(it, now).toString()))
        }
        activeWarOf(own.id)?.let { war ->
            val other = war.otherTeam(own.id)
            val team = war.teamOf(own.id)
            val otherNames = mapOf("tag" to other?.tag.orEmpty(), "name" to other?.name.orEmpty())
            if (war.state == WarState.QUEUED || war.state == WarState.ASSIGNED) {
                lines += Msg("clanwar.status.queued", otherNames + mapOf("position" to queuePosition(war).toString(), "progress" to poolProgress(now).toString()))
            } else {
                val state = live[war.warId]
                lines += Msg(
                    "clanwar.status.war",
                    otherNames + mapOf(
                        "pointsOwn" to (state?.points?.get(team?.id) ?: 0).toString(),
                        "pointsOther" to (state?.points?.get(other?.id) ?: 0).toString(),
                        "present" to (state?.present?.get(team?.id) ?: 0).toString(),
                        "max" to settings().maxPlayersPerClan.toString(),
                    ),
                    mapOf("phase" to "clanwar.phase.${war.state}"),
                )
            }
        }
        if (lines.size == 1) lines += Msg("clanwar.status.none")
        return Outcome(lines)
    }

    /**
     * Reminds an owner/officer joining the network of open incoming challenges.
     *
     * @param player the joined player.
     * @return messages to send.
     */
    @Synchronized
    fun onJoin(player: String): List<WarEffect> {
        val now = clock()
        val clanId = clans.clanIdOf(player) ?: return emptyList()
        val incoming = open(now).filter { it.to.id == clanId }
        if (incoming.isEmpty()) return emptyList()
        val view = clans.detail(clanId) ?: return emptyList()
        if (!view.isOfficer(player)) return emptyList()
        return incoming.map { WarEffect.Message(player, Msg("clanwar.challenge.reminder", refNames(it.from) + ("minutes" to minutesLeft(it, now).toString()))) }
    }

    // ------------------------------------------------------------------ war servers

    /**
     * A war server's poll (`mineclash.war.poll`): takes its status, advances the wars and answers the complete
     * desired state for that server.
     *
     * @param serviceId the polling service.
     * @param status its status.
     * @return desired state and effects.
     */
    @Synchronized
    fun poll(serviceId: String, status: WarServerStatus): PollAnswer {
        val now = clock()
        val effects = mutableListOf<WarEffect>()
        val previous = servers[serviceId]
        if (previous != null && previous.bootId != status.bootId) cancels.remove(serviceId)
        // A different boot id than the one a war was handed to means that server restarted and lost its worlds –
        // also after a node restart, when [servers] starts empty (contract §4).
        wars.values.filter { it.serviceId == serviceId && it.state in WarState.HOSTED && it.bootId != null && it.bootId != status.bootId }
            .forEach { war ->
                if (war.state == WarState.ASSIGNED) {
                    requeue(war, now, skip = false)
                } else {
                    cancelWar(war, WarProtocol.CancelReason.SERVER_LOST, now, effects, tellServer = false)
                }
            }
        servers[serviceId] = Server(status.bootId, status.capacity.coerceAtLeast(1), status.pool, now, status.wars.map { it.warId }.toSet())
        status.wars.forEach { hosted -> applyReport(serviceId, hosted, now, effects) }
        status.rejected.forEach { warId ->
            val war = wars[warId]
            if (war != null && war.serviceId == serviceId && war.state == WarState.ASSIGNED) requeue(war, now)
        }
        advance(now, effects)
        val specs = wars.values.filter { it.serviceId == serviceId && it.state in WarState.HOSTED }.map(::spec)
        return PollAnswer(WarDesiredState(returnTo(), specs, takeCancels(serviceId, status)), effects)
    }

    /**
     * A player logs in to a war server (`mineclash.war.arrive`): admits rostered players and rosters clan
     * members within the limit.
     *
     * @param serviceId the war server.
     * @param uuid player uuid.
     * @param name player name.
     * @return the decision.
     */
    @Synchronized
    fun arrive(serviceId: String, uuid: String, name: String): ArrivalDecision {
        val candidates = wars.values.filter { it.serviceId == serviceId && it.state in WarState.HOSTED }
        for (war in candidates) {
            val team = war.teams.firstOrNull { it.rostered(uuid, name) } ?: continue
            if (war.state == WarState.ASSIGNED) return ArrivalDecision(refusal = Msg("clanwar.join.not_ready"))
            val known = team.members.map { if (it.name.equals(name, ignoreCase = true) && it.uuid.isBlank()) it.copy(uuid = uuid) else it }
            if (known != team.members) put(war.withTeam(team.copy(members = known)))
            return ArrivalDecision(war.warId, team.id)
        }
        val clanId = clans.clanIdOf(name)
        val war = candidates.firstOrNull { candidate -> candidate.teams.any { it.clanId == clanId } }
            ?: return ArrivalDecision(refusal = Msg("war.arrive.no_war"))
        if (war.state == WarState.ASSIGNED) return ArrivalDecision(refusal = Msg("clanwar.join.not_ready"))
        val team = war.teamOf(clanId!!)!!
        val view = clans.detail(clanId)
        if (view == null || !view.has(name)) return ArrivalDecision(refusal = Msg("clanwar.join.not_member"))
        val max = settings().maxPlayersPerClan.coerceAtLeast(1)
        if (team.members.size >= max) return ArrivalDecision(refusal = Msg("clanwar.join.full", mapOf("max" to max.toString())))
        put(war.withTeam(team.copy(members = team.members + WarMember(uuid, name))))
        return ArrivalDecision(war.warId, team.id)
    }

    /**
     * A war server reports a result (`mineclash.war.finish`), idempotent per war.
     *
     * @param serviceId the reporting server.
     * @param result the result.
     * @return `recorded` or `duplicate` and the effects (payout, messages).
     */
    @Synchronized
    fun finish(serviceId: String, result: WarResult): FinishAnswer {
        val now = clock()
        val war = wars[result.warId] ?: return FinishAnswer(DUPLICATE, emptyList())
        val effects = mutableListOf<WarEffect>()
        if (result.reason == WarProtocol.EndReason.CANCELLED) {
            val record = war.copy(state = WarState.CANCELLED, reason = result.reason, endedAt = result.endedAt, points = result.points, participants = result.participants)
            // Persist first: a failing save leaves the war in memory, the server's outbox retries.
            store.save(record)
            forget(war.warId)
            war.teams.forEach { team ->
                val other = war.teams.first { it.id != team.id }
                notifyTeam(team, Msg("clanwar.cancelled", mapOf("tag" to other.tag, "name" to other.name), mapOf("reason" to "clanwar.reason.CANCELLED")), effects)
            }
            return FinishAnswer(RECORDED, effects)
        }
        val record = war.copy(
            state = WarState.FINISHED,
            winner = result.winner?.takeIf { winner -> war.teams.any { it.id == winner } },
            reason = result.reason,
            points = result.points,
            participants = result.participants,
            runningAt = result.runningAt ?: war.runningAt,
            endedAt = result.endedAt.takeIf { it > 0 } ?: now,
        )
        store.save(record)
        forget(war.warId)
        effects += payEffect(record)
        val a = war.teams[0]
        val b = war.teams[1]
        val points = "${result.points[a.id] ?: 0} : ${result.points[b.id] ?: 0}"
        val reasonRef = mapOf("reason" to "clanwar.reason.${result.reason}")
        val message = when (val winner = record.winner) {
            null -> Msg("clanwar.result.draw", mapOf("tagA" to a.tag, "tagB" to b.tag, "points" to points), reasonRef)
            else -> {
                val won = war.teams.first { it.id == winner }
                val lost = war.teams.first { it.id != winner }
                Msg(
                    "clanwar.result.won",
                    mapOf("tag" to won.tag, "name" to won.name, "otherTag" to lost.tag, "otherName" to lost.name,
                        "points" to "${result.points[won.id] ?: 0} : ${result.points[lost.id] ?: 0}"),
                    reasonRef,
                )
            }
        }
        war.teams.forEach { notifyTeam(it, message, effects) }
        return FinishAnswer(RECORDED, effects)
    }

    /**
     * Marks the payout of a finished war as done (called by [WarEffectRunner] after paying).
     *
     * @param warId war id.
     */
    @Synchronized
    fun markPaid(warId: String) {
        val record = store.load(warId) ?: return
        if (record.state != WarState.FINISHED || record.paidAt != null) return
        store.save(record.copy(paidAt = clock()))
    }

    /**
     * Payouts of finished wars that did not run before the node stopped (`paidAt` missing).
     *
     * @return pay effects to run once at start.
     */
    @Synchronized
    fun pendingPayouts(): List<WarEffect> = store.loadUnpaid().map(::payEffect)

    /**
     * Periodic work (every few seconds): expiries, timeouts, lost servers, membership checks and assignments.
     *
     * @return effects to run.
     */
    @Synchronized
    fun tick(): List<WarEffect> {
        val effects = mutableListOf<WarEffect>()
        advance(clock(), effects)
        return effects
    }

    /**
     * Staff cancel (`mineclash.war.cancel <warId>`).
     *
     * @param warId war id.
     * @return a plain line and the effects.
     */
    @Synchronized
    fun adminCancel(warId: String): Outcome {
        val war = wars[warId] ?: return Outcome(listOf(Msg("clanwar.admin.no_war", mapOf("warId" to warId))))
        val effects = mutableListOf<WarEffect>()
        cancelWar(war, WarProtocol.CancelReason.ADMIN, clock(), effects)
        return Outcome(listOf(Msg("clanwar.admin.cancelled", mapOf("warId" to warId))), effects)
    }

    /**
     * Staff overview (`mineclash.war.status`): active wars, challenges and war servers.
     *
     * @return the overview as JSON.
     */
    @Synchronized
    fun snapshot(): JsonObject {
        val now = clock()
        return buildJsonObject {
            put("wars", buildJsonArray {
                wars.values.forEach { war ->
                    add(buildJsonObject {
                        put("warId", war.warId)
                        put("state", war.state)
                        put("service", war.serviceId)
                        put("teams", buildJsonArray {
                            war.teams.forEach { team ->
                                add(buildJsonObject {
                                    put("id", team.id)
                                    put("tag", team.tag)
                                    put("name", team.name)
                                    put("roster", team.members.size)
                                    put("points", live[war.warId]?.points?.get(team.id) ?: 0)
                                    put("present", live[war.warId]?.present?.get(team.id) ?: 0)
                                })
                            }
                        })
                        put("acceptedAt", war.acceptedAt)
                    })
                }
            })
            put("challenges", buildJsonArray {
                challenges.forEach { add(buildJsonObject { put("from", it.from.tag); put("to", it.to.tag); put("expiresIn", (it.expiresAt - now) / 1000) }) }
            })
            put("servers", buildJsonArray {
                servers.forEach { (id, server) ->
                    add(buildJsonObject {
                        put("service", id)
                        put("bootId", server.bootId)
                        put("capacity", server.capacity)
                        put("ready", server.pool.ready)
                        put("target", server.pool.target)
                        put("generating", server.pool.generating)
                        put("progress", server.pool.progress)
                        put("hosted", server.hosted.size)
                        put("lastPollMillisAgo", now - server.lastPollAt)
                    })
                }
            })
        }
    }

    /**
     * The active wars (for tests and the release reset).
     *
     * @return wars that still occupy their clans.
     */
    @Synchronized
    fun active(): List<WarRecord> = wars.values.toList()

    // ------------------------------------------------------------------ internals

    private fun usage(): Outcome = reply(Msg("clanwar.usage"))

    private fun reply(msg: Msg): Outcome = Outcome(listOf(msg))

    private fun startWar(challenge: Challenge, accepting: ClanView, challenger: ClanView, now: Long): Outcome {
        val effects = mutableListOf<WarEffect>()
        val superseded = challenges.filter { it !== challenge && (it.from.id in setOf(accepting.id, challenger.id) || it.to.id in setOf(accepting.id, challenger.id)) }
        challenges.remove(challenge)
        challenges.removeAll(superseded.toSet())
        superseded.forEach { other -> notifySuperseded(other, setOf(accepting.id, challenger.id), effects) }
        val day = DAY.format(Instant.ofEpochMilli(now))
        val record = WarRecord(
            warId = store.nextId(day),
            state = WarState.QUEUED,
            teams = listOf(
                WarTeamRecord("A", challenger.id, challenger.name, challenger.tag),
                WarTeamRecord("B", accepting.id, accepting.name, accepting.tag),
            ),
            createdAt = challenge.createdAt,
            acceptedAt = now,
        )
        put(record)
        live[record.warId] = Live(now)
        val accepted = Msg("clanwar.accepted", mapOf("tagA" to challenger.tag, "nameA" to challenger.name, "tagB" to accepting.tag, "nameB" to accepting.name))
        (onlineMembers(challenger) + onlineMembers(accepting)).distinct().forEach { effects += WarEffect.Message(it, accepted) }
        advance(now, effects)
        val after = wars[record.warId]
        if (after != null && after.state == WarState.QUEUED) {
            val waiting = if (aliveServers(now).isEmpty()) {
                Msg("clanwar.service_starting")
            } else {
                Msg("clanwar.queued", mapOf("position" to queuePosition(after).toString()))
            }
            (onlineMembers(challenger) + onlineMembers(accepting)).distinct().forEach { effects += WarEffect.Message(it, waiting) }
        }
        return Outcome(emptyList(), effects)
    }

    private fun notifySuperseded(challenge: Challenge, atWar: Set<String>, effects: MutableList<WarEffect>) {
        if (challenge.to.id in atWar) {
            clans.detail(challenge.to.id)?.let { view ->
                onlineMembers(view) { view.isOfficer(it) }.forEach { effects += WarEffect.Message(it, Msg("clanwar.challenge.superseded", refNames(challenge.from))) }
            }
            if (challenge.from.id !in atWar) {
                clans.detail(challenge.from.id)?.let { view ->
                    onlineMembers(view).forEach { effects += WarEffect.Message(it, Msg("clanwar.challenge.dropped", refNames(challenge.to))) }
                }
            }
        } else {
            clans.detail(challenge.to.id)?.let { view ->
                onlineMembers(view) { view.isOfficer(it) }.forEach { effects += WarEffect.Message(it, Msg("clanwar.challenge.withdrawn", refNames(challenge.from))) }
            }
        }
    }

    /** Expiries, timeouts, lost servers, membership, assignments – shared by [tick] and [poll]. */
    private fun advance(now: Long, effects: MutableList<WarEffect>) {
        expireChallenges(now, effects)
        val config = settings()
        val lostMillis = config.serverLostSeconds.coerceAtLeast(10) * 1000L
        servers.entries.filter { now - it.value.lastPollAt > lostMillis }.map { it.key }.forEach { serviceId ->
            servers.remove(serviceId)
            cancels.remove(serviceId)
            wars.values.filter { it.serviceId == serviceId && it.state in WarState.HOSTED }.forEach {
                cancelWar(it, WarProtocol.CancelReason.SERVER_LOST, now, effects, tellServer = false)
            }
        }
        wars.values.filter { it.state == WarState.ASSIGNED && now - (it.assignedAt ?: now) > ASSIGN_TIMEOUT_MILLIS }.forEach { requeue(it, now) }
        wars.values.filter { (it.state == WarState.PREPARE || it.state == WarState.RUNNING) && now - (live[it.warId]?.lastReportedAt ?: now) > lostMillis }
            .forEach { cancelWar(it, WarProtocol.CancelReason.SERVER_LOST, now, effects) }
        val queueMillis = config.queueTimeoutMinutes.coerceAtLeast(1) * 60_000L
        wars.values.filter { it.state == WarState.QUEUED && now - it.acceptedAt > queueMillis }
            .forEach { cancelWar(it, WarProtocol.CancelReason.QUEUE_TIMEOUT, now, effects) }
        if (now - lastMembershipCheck >= MEMBERSHIP_CHECK_MILLIS) {
            lastMembershipCheck = now
            checkMembership(now, effects)
        }
        assign(now, effects)
        skips.entries.removeIf { it.value <= now }
    }

    private fun expireChallenges(now: Long, effects: MutableList<WarEffect>) {
        val expired = challenges.filter { it.expiresAt <= now }
        if (expired.isEmpty()) return
        challenges.removeAll(expired.toSet())
        expired.forEach { challenge ->
            clans.detail(challenge.from.id)?.let { view ->
                onlineMembers(view).forEach { effects += WarEffect.Message(it, Msg("clanwar.challenge.expired", refNames(challenge.to))) }
            }
            clans.detail(challenge.to.id)?.let { view ->
                onlineMembers(view) { view.isOfficer(it) }.forEach { effects += WarEffect.Message(it, Msg("clanwar.challenge.expired", refNames(challenge.from))) }
            }
        }
    }

    /** Challenges that have not expired yet (expired ones are announced and removed by the next tick). */
    private fun open(now: Long): List<Challenge> = challenges.filter { it.expiresAt > now }

    private fun checkMembership(now: Long, effects: MutableList<WarEffect>) {
        if (!clans.reachable()) return
        for (war in wars.values.toList()) {
            var current = war
            var disbanded = false
            for (team in war.teams) {
                val key = "${war.warId}|${team.clanId}"
                val view = when (val read = clans.read(team.clanId)) {
                    is ClanRead.Found -> read.view.also { missingClans.remove(key) }
                    ClanRead.Failed -> null
                    ClanRead.Missing -> {
                        // Only a repeated "no such clan" counts – one odd answer must not end a running war.
                        val misses = (missingClans[key] ?: 0) + 1
                        missingClans[key] = misses
                        if (misses >= DISBAND_CONFIRMATIONS) disbanded = true
                        null
                    }
                }
                if (disbanded) break
                if (view == null) continue
                val left = team.members.filter { !view.has(it.name) }
                if (left.isEmpty()) continue
                current = current.withTeam(team.copy(members = team.members - left.toSet()))
                left.forEach { member -> if (isOnline(member.name)) effects += WarEffect.Message(properName(member.name), Msg("clanwar.member_removed")) }
            }
            if (disbanded) {
                missingClans.keys.removeIf { it.startsWith("${war.warId}|") }
                cancelWar(current, WarProtocol.CancelReason.DISBANDED, now, effects)
            } else if (current != war) {
                put(current)
            }
        }
    }

    private fun assign(now: Long, effects: MutableList<WarEffect>) {
        val alive = aliveServers(now)
        val load = alive.keys.associateWith { id -> wars.values.count { it.serviceId == id && it.state in WarState.HOSTED } }.toMutableMap()
        val pending = alive.keys.associateWith { id ->
            wars.values.count { it.serviceId == id && it.state == WarState.ASSIGNED && it.warId !in alive.getValue(id).hosted }
        }.toMutableMap()
        var unassigned = 0
        for (war in wars.values.filter { it.state == WarState.QUEUED }.sortedBy { it.acceptedAt }) {
            val candidate = alive.entries
                .filter { (id, server) ->
                    (skips["${war.warId}|$id"] ?: 0) <= now && load.getValue(id) < server.capacity && server.pool.ready - pending.getValue(id) > 0
                }
                .sortedWith(compareByDescending<Map.Entry<String, Server>> { it.value.pool.ready - pending.getValue(it.key) }.thenBy { load.getValue(it.key) })
                .firstOrNull()
            if (candidate == null) {
                unassigned++
                continue
            }
            put(war.copy(state = WarState.ASSIGNED, serviceId = candidate.key, bootId = candidate.value.bootId, assignedAt = now))
            load[candidate.key] = load.getValue(candidate.key) + 1
            pending[candidate.key] = pending.getValue(candidate.key) + 1
        }
        if (unassigned == 0) return
        val allFull = alive.isEmpty() || alive.all { (id, server) -> load.getValue(id) >= server.capacity }
        if (allFull && now - lastServiceStart >= SERVICE_START_COOLDOWN_MILLIS) {
            lastServiceStart = now
            effects += WarEffect.StartService(settings().warTask)
        }
    }

    private fun preStartService(now: Long, effects: MutableList<WarEffect>) {
        if (aliveServers(now).isNotEmpty() || now - lastServiceStart < SERVICE_START_COOLDOWN_MILLIS) return
        lastServiceStart = now
        effects += WarEffect.StartService(settings().warTask)
    }

    private fun applyReport(serviceId: String, hosted: HostedWar, now: Long, effects: MutableList<WarEffect>) {
        var war = wars[hosted.warId] ?: return
        if (war.serviceId != serviceId) {
            // A queued war that an alive server already runs (it was requeued while the server hosted it, e.g. after a
            // node restart or a slow poll): adopt it instead of reassigning it somewhere else.
            val adoptable = war.state == WarState.QUEUED &&
                (hosted.phase == WarProtocol.Phase.PREPARE || hosted.phase == WarProtocol.Phase.RUNNING)
            if (!adoptable) return
            war = war.copy(state = WarState.ASSIGNED, serviceId = serviceId, bootId = servers[serviceId]?.bootId, assignedAt = now)
            skips.remove("${war.warId}|$serviceId")
            put(war)
        }
        val state = live.getOrPut(war.warId) { Live(now) }
        state.lastReportedAt = now
        state.points = hosted.points
        state.present = hosted.present
        when (hosted.phase) {
            WarProtocol.Phase.PREPARE -> if (war.state == WarState.ASSIGNED) {
                put(war.copy(state = WarState.PREPARE, prepareEndsAt = hosted.phaseEndsAt))
                val seconds = hosted.phaseEndsAt?.let { ((it - now + 999) / 1000).coerceAtLeast(0) } ?: settings().prepareSeconds.toLong()
                war.teams.forEach { notifyTeam(it, Msg("clanwar.ready", mapOf("seconds" to seconds.toString())), effects) }
            }
            WarProtocol.Phase.RUNNING -> if (war.state == WarState.ASSIGNED || war.state == WarState.PREPARE) {
                put(war.copy(state = WarState.RUNNING, runningAt = now))
            }
        }
        if (hosted.phase == WarProtocol.Phase.RUNNING) warnAbsentTeams(war, state, hosted, now, effects)
    }

    /**
     * Abandon warnings (contract §4): the war server only sees the players on it, but an absent clan is by definition
     * elsewhere – the node derives the absence from the reported `present` counts and tells the clan's online members
     * at 5 and 10 minutes (thresholds below the limit only).
     */
    private fun warnAbsentTeams(war: WarRecord, state: Live, hosted: HostedWar, now: Long, effects: MutableList<WarEffect>) {
        val limit = settings().abandonMinutes.coerceAtLeast(1)
        for (team in war.teams) {
            if ((hosted.present[team.id] ?: 0) > 0) {
                state.absentSince.remove(team.id)
                state.warned.remove(team.id)
                continue
            }
            val since = state.absentSince.getOrPut(team.id) { now }
            val done = state.warned.getOrPut(team.id) { mutableSetOf() }
            ABANDON_WARN_MINUTES.filter { it < limit && now - since >= it * 60_000L && done.add(it) }.forEach { minutes ->
                notifyTeam(team, Msg("war.abandon.warning_own", mapOf("minutes" to minutes.toString(), "limit" to limit.toString())), effects)
            }
        }
    }

    private fun requeue(war: WarRecord, now: Long, skip: Boolean = true) {
        if (skip) war.serviceId?.let { skips["${war.warId}|$it"] = now + SKIP_MILLIS }
        put(war.copy(state = WarState.QUEUED, serviceId = null, bootId = null, assignedAt = null))
    }

    private fun cancelWar(war: WarRecord, reason: String, now: Long, effects: MutableList<WarEffect>, tellServer: Boolean = true) {
        // Persist first: if the save fails the war stays in memory and the next tick tries again.
        store.save(war.copy(state = WarState.CANCELLED, cancelReason = reason, endedAt = now))
        forget(war.warId)
        val serviceId = war.serviceId
        if (tellServer && serviceId != null && war.state in WarState.HOSTED) {
            cancels.getOrPut(serviceId) { mutableListOf() } += PendingCancel(WarCancel(war.warId, reason))
        }
        war.teams.forEach { team ->
            val other = war.teams.firstOrNull { it.id != team.id } ?: return@forEach
            notifyTeam(team, Msg("clanwar.cancelled", mapOf("tag" to other.tag, "name" to other.name), mapOf("reason" to "clanwar.cancel_reason.$reason")), effects)
        }
    }

    /** Pending cancels of a server; each is repeated until the server no longer hosts the war (at most a few polls). */
    private fun takeCancels(serviceId: String, status: WarServerStatus): List<WarCancel> {
        val pending = cancels[serviceId] ?: return emptyList()
        val hosted = status.wars.map { it.warId }.toSet()
        pending.removeAll { it.polls > 0 && (it.cancel.warId !in hosted || it.polls >= CANCEL_REPEAT_POLLS) }
        pending.forEach { it.polls++ }
        if (pending.isEmpty()) cancels.remove(serviceId)
        return pending.map { it.cancel }
    }

    private fun spec(war: WarRecord): WarSpec {
        val config = settings()
        return WarSpec(war.warId, config.prepareSeconds, config.maxPlayersPerClan, config.abandonMinutes, war.teams.map { it.spec() })
    }

    private fun forget(warId: String) {
        wars.remove(warId)
        live.remove(warId)
        missingClans.keys.removeIf { it.startsWith("$warId|") }
    }

    private fun payEffect(record: WarRecord): WarEffect.PayWar {
        val roster = record.participants.takeIf { it.isNotEmpty() }?.associate { it.uuid to it.team }
            ?: record.teams.flatMap { team -> team.members.map { it.uuid to team.id } }.toMap()
        val names = record.participants.associate { it.uuid to it.name } +
            record.teams.flatMap { it.members }.associate { it.uuid to it.name }
        return WarEffect.PayWar(record.warId, roster.filterKeys { it.isNotBlank() }, names, record.winner)
    }

    private fun put(record: WarRecord) {
        wars[record.warId] = record
        store.save(record)
    }

    private fun activeWarOf(clanId: String): WarRecord? = wars.values.firstOrNull { war -> war.teams.any { it.clanId == clanId } }

    private fun ownClan(player: String): ClanView? =
        clans.clanIdOf(player)?.let { clans.detail(it) }?.takeIf { it.has(player) }

    private fun aliveServers(now: Long): Map<String, Server> = servers.filterValues { now - it.lastPollAt <= ALIVE_MILLIS }

    private fun queuePosition(war: WarRecord): Int =
        wars.values.filter { it.state == WarState.QUEUED || it.state == WarState.ASSIGNED }.sortedBy { it.acceptedAt }.indexOfFirst { it.warId == war.warId } + 1

    private fun poolProgress(now: Long): Int =
        ((aliveServers(now).values.maxOfOrNull { it.pool.progress } ?: 0.0) * 100).toInt().coerceIn(0, 100)

    private fun minutesLeft(challenge: Challenge, now: Long): Long = ((challenge.expiresAt - now + 59_999) / 60_000).coerceAtLeast(1)

    private fun onlineByName(): Map<String, OnlinePlayer> = online().associateBy { it.name.lowercase() }

    private fun isOnline(name: String): Boolean = name.lowercase() in onlineByName()

    private fun properName(name: String): String = onlineByName()[name.lowercase()]?.name ?: name

    private fun onlineMembers(view: ClanView, filter: (String) -> Boolean = { true }): List<String> {
        val byName = onlineByName()
        return view.members.keys.mapNotNull { byName[it]?.name }.filter(filter)
    }

    private fun notifyTeam(team: WarTeamRecord, msg: Msg, effects: MutableList<WarEffect>) {
        val recipients = clans.detail(team.clanId)?.let { onlineMembers(it) }
            ?: team.members.map { it.name }.filter(::isOnline).map(::properName)
        recipients.forEach { effects += WarEffect.Message(it, msg) }
    }

    private fun ref(view: ClanView) = ClanRef(view.id, view.name, view.tag)

    private fun names(view: ClanView): Map<String, String> = mapOf("tag" to view.tag, "name" to view.name)

    private fun refNames(ref: ClanRef): Map<String, String> = mapOf("tag" to ref.tag, "name" to ref.name)

    /** Answers and timings (contract §4). */
    companion object {
        /** Answer of a stored result. */
        const val RECORDED: String = "recorded"

        /** Answer of a result for a war that is already final or unknown. */
        const val DUPLICATE: String = "duplicate"

        /** A war server counts as alive while it polled within this time. */
        const val ALIVE_MILLIS: Long = 10_000

        /** An assigned war the server does not confirm in this time goes back to the queue. */
        const val ASSIGN_TIMEOUT_MILLIS: Long = 30_000

        /** After a timeout or rejection the server is skipped for that war this long. */
        const val SKIP_MILLIS: Long = 60_000

        /** Minimum time between two automatic war-server starts. */
        const val SERVICE_START_COOLDOWN_MILLIS: Long = 120_000

        /** Interval of the clan membership check. */
        const val MEMBERSHIP_CHECK_MILLIS: Long = 30_000

        /** A cancel is repeated at most this many polls. */
        const val CANCEL_REPEAT_POLLS: Int = 5

        /** Consecutive "no such clan" answers before a war is cancelled as disbanded. */
        const val DISBAND_CONFIRMATIONS: Int = 3

        /** Absence minutes at which the absent clan is warned (only those below the abandon limit). */
        val ABANDON_WARN_MINUTES: List<Int> = listOf(5, 10)

        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    }
}
