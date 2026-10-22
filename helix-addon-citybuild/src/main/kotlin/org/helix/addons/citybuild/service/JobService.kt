package org.helix.addons.citybuild.service

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.helix.addons.citybuild.model.JobProfile
import org.helix.addons.citybuild.model.JobProgress
import kotlin.math.pow

/**
 * Network-wide job state: active job slots with switch cooldown, progress per job, batched payouts
 * within the daily cap and one-time milestone rewards. XP/level arithmetic runs on the instances;
 * [xpForLevel] is the shared curve.
 *
 * @property ctx shared dependencies.
 * @property rewards milestone payouts.
 */
class JobService(private val ctx: CityContext, private val rewards: RewardService) {
    /**
     * Loads (or creates) the job state of a player, normalized to `jobs.maxActive` slots.
     *
     * @param uuid player.
     * @return the state.
     */
    fun load(uuid: String): JobProfile = normalize(ctx.repository.jobProfile(uuid) ?: JobProfile())

    /**
     * Stores job progress (merged over the stored progress; unknown job ids are dropped); active
     * slots, switch times and milestones are kept from the store.
     *
     * @param uuid player.
     * @param jobs progress per job.
     * @return the stored state.
     */
    @Synchronized
    fun saveProgress(uuid: String, jobs: Map<String, JobProgress>): JobProfile {
        val maxLevel = ctx.int("jobs.maxLevel", 50)
        val known = ctx.config.documents("jobs").map { it.id }.toSet()
        val cleaned = jobs.filterKeys { it in known }.mapValues { (_, p) -> JobProgress(p.level.coerceIn(1, maxLevel), p.xp.coerceAtLeast(0)) }
        val current = load(uuid)
        val updated = current.copy(jobs = current.jobs + cleaned)
        ctx.repository.putJobProfile(uuid, updated)
        return updated
    }

    /**
     * Puts a job into a slot (or empties it with `none`).
     *
     * @param uuid player.
     * @param slot slot index (0 until `jobs.maxActive`).
     * @param job job id or `none`.
     * @param bypass ignore the switch cooldown.
     * @return the stored state.
     */
    @Synchronized
    fun select(uuid: String, slot: Int, job: String, bypass: Boolean = false): JobProfile {
        val profile = load(uuid)
        if (slot !in profile.active.indices) Results.fail("invalid_slot", "slot must be 0..${profile.active.size - 1}")
        val target = job.takeUnless { it.equals("none", true) }
        if (target != null && ctx.doc("jobs", target) == null) Results.fail("not_found", "no job '$target'")
        if (target != null && profile.active.withIndex().any { (i, j) -> i != slot && j == target }) Results.fail("already_active", "job already active")
        if (profile.active[slot] == target) return profile
        val cooldown = ctx.int("jobs.switchCooldownHours", 24) * 3_600_000L
        val switched = profile.switchedAt[slot]
        if (!bypass && profile.active[slot] != null && switched > 0 && ctx.now() < switched + cooldown) {
            Results.fail("cooldown", ((switched + cooldown - ctx.now() + 999) / 1000).toString())
        }
        val updated = profile.copy(
            active = profile.active.toMutableList().also { it[slot] = target },
            switchedAt = profile.switchedAt.toMutableList().also { it[slot] = ctx.now() },
            jobs = if (target != null && target !in profile.jobs) profile.jobs + (target to JobProgress()) else profile.jobs,
        )
        ctx.repository.putJobProfile(uuid, updated)
        return updated
    }

    /**
     * Pays a batched job payout, clamped to what is left of the daily cap.
     *
     * @param uuid player.
     * @param kronen requested whole Kronen.
     * @param job job id (ledger reason).
     * @return `{paid, remaining, capped}`.
     */
    @Synchronized
    fun payout(uuid: String, kronen: Long, job: String): JsonObject {
        val cap = ctx.long("jobs.dailyCap", 4000)
        val day = ctx.today().toString()
        val earned = ctx.repository.counters(uuid, day)[COUNTER] ?: 0
        val amount = minOf(kronen.coerceAtLeast(0), cap - earned).coerceAtLeast(0)
        if (amount > 0) {
            val money = ctx.kronen.deposit(uuid, amount, "job:$job", "jobs:$day", LIMIT_KEY, cap)
            if (!money.ok) return jsonOf("paid" to 0, "remaining" to (cap - earned).coerceAtLeast(0), "capped" to (money.code == "limit"), "code" to money.code)
            ctx.repository.addCounter(uuid, day, COUNTER, amount, Long.MAX_VALUE)
        }
        val remaining = (cap - earned - amount).coerceAtLeast(0)
        return jsonOf("paid" to amount, "remaining" to remaining, "capped" to (amount < kronen))
    }

    /**
     * Pays a level milestone once (rewards document `job_<level>`).
     *
     * @param uuid player.
     * @param job job id.
     * @param level reached level (must be in `jobs.milestones` and not above the stored level).
     * @return the reward body plus `{paid}`.
     */
    @Synchronized
    fun milestone(uuid: String, job: String, level: Int): JsonObject {
        if (level !in ctx.ints("jobs.milestones")) Results.fail("not_milestone", "level $level has no milestone")
        val profile = load(uuid)
        if ((profile.jobs[job]?.level ?: 0) < level) Results.fail("level_not_reached", "job level below $level")
        val key = "$job:$level"
        if (key in profile.milestones) Results.fail("already_claimed", key)
        ctx.repository.putJobProfile(uuid, profile.copy(milestones = profile.milestones + key))
        return runCatching { rewards.payDocument(uuid, "job_$level", "job.milestone:$key") }
            .getOrElse {
                ctx.repository.putJobProfile(uuid, profile)
                throw it
            }
    }

    /**
     * XP needed to advance from [level] to the next level (`levelBase × level^levelExponent`).
     *
     * @param level current level.
     * @return XP.
     */
    fun xpForLevel(level: Int): Long {
        val base = ctx.int("jobs.levelBase", 100)
        val exponent = ctx.config.setting("jobs.levelExponent")?.toDoubleOrNull() ?: 1.6
        return (base * level.toDouble().pow(exponent)).toLong()
    }

    private fun normalize(profile: JobProfile): JobProfile {
        val slots = ctx.int("jobs.maxActive", 2).coerceAtLeast(1)
        return profile.copy(
            active = List(slots) { profile.active.getOrNull(it) },
            switchedAt = List(slots) { profile.switchedAt.getOrNull(it) ?: 0L },
        )
    }

    /**
     * Registers the job actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        val json = CityContext.json
        r.register("citybuild.jobs.load", "citybuild.jobs.load <uuid>", "Job state of a player.", null) { a ->
            Results.ok(json.encodeToJsonElement(load(a.str(0, "uuid"))))
        }
        r.register("citybuild.jobs.save", "citybuild.jobs.save <uuid> <json>", "Stores job progress {jobs:{id:{level,xp}}}.", null) { a ->
            val jobs = a.json(1)["jobs"]?.jsonObject ?: Results.fail("invalid", "jobs missing")
            val progress = json.decodeFromJsonElement(MapSerializer(String.serializer(), JobProgress.serializer()), jobs)
            Results.ok(json.encodeToJsonElement(saveProgress(a.str(0, "uuid"), progress)))
        }
        r.register("citybuild.jobs.select", "citybuild.jobs.select <uuid> <slot> <job|none> [bypass]", "Puts a job into a slot.", null) { a ->
            Results.ok(json.encodeToJsonElement(select(a.str(0, "uuid"), a.int(1, "slot"), a.str(2, "job"), a.opt(3).equals("bypass", true))))
        }
        r.register("citybuild.jobs.payout", "citybuild.jobs.payout <uuid> <kronen> <job>", "Pays a batched job payout within the daily cap.", null) { a ->
            Results.ok(payout(a.str(0, "uuid"), a.long(1, "kronen"), a.str(2, "job")))
        }
        r.register("citybuild.jobs.milestone", "citybuild.jobs.milestone <uuid> <job> <level>", "Pays a level milestone once.", null) { a ->
            Results.ok(milestone(a.str(0, "uuid"), a.str(1, "job"), a.int(2, "level")))
        }
        r.register("citybuild.jobs.curve", "citybuild.jobs.curve", "XP needed per level (index 0 = level 1).", null) { _ ->
            Results.ok(toJson((1..ctx.int("jobs.maxLevel", 50)).map { xpForLevel(it) }))
        }
    }

    private companion object {
        const val COUNTER = "kronen:jobs"
        const val LIMIT_KEY = "citybuild.jobs"
    }
}
