package org.helix.addons.citybuild.service

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.helix.addons.citybuild.model.RewardState
import java.time.LocalDate

/**
 * Daily reward streak (network-wide, once per calendar day in `general.zone`) and the one-time
 * tutorial bonus. Reward bodies come from the `rewards` collection (`day1`…`dayN`, `tutorial`,
 * `job_<level>`): the node pays the `kronen`, the instance hands out `items` and crate `keys`.
 *
 * @property ctx shared dependencies.
 */
class RewardService(private val ctx: CityContext) {
    /**
     * State of the streak.
     *
     * @param uuid player.
     * @return `{claimedToday, streak, nextDay, lastDay}`.
     */
    fun state(uuid: String): JsonObject {
        val current = ctx.repository.reward(uuid)
        val today = ctx.today()
        val claimedToday = current?.lastDay == today.toString()
        return jsonOf(
            "claimedToday" to claimedToday,
            "streak" to (current?.streak ?: 0),
            "nextDay" to if (claimedToday) nextDay(current, today.plusDays(1)) else nextDay(current, today),
            "lastDay" to current?.lastDay,
        )
    }

    /**
     * Claims today's reward.
     *
     * @param uuid player.
     * @return the reward body plus `{day, paid}`.
     */
    fun claim(uuid: String): JsonObject {
        val current = ctx.repository.reward(uuid)
        val today = ctx.today()
        if (current?.lastDay == today.toString()) Results.fail("already_claimed", "reward already claimed today")
        val day = nextDay(current, today)
        if (!ctx.repository.updateReward(uuid, current?.lastDay, RewardState(day, today.toString()))) {
            Results.fail("already_claimed", "reward claimed concurrently")
        }
        return runCatching { payDocument(uuid, "day$day", "reward:$today").with("day" to day) }
            .getOrElse { error ->
                // Undo the claim so the player can retry once the economy is back.
                ctx.repository.updateReward(uuid, today.toString(), current ?: RewardState())
                throw error
            }
    }

    /**
     * Completes the tutorial; the bonus is paid only the first time.
     *
     * @param uuid player.
     * @return the tutorial reward body plus `{paid}` (`paid = false` when already paid before).
     */
    fun completeTutorial(uuid: String): JsonObject {
        if (!ctx.repository.markTutorialBonus(uuid, ctx.now())) return jsonOf("paid" to false, "kronen" to 0)
        return runCatching { payDocument(uuid, "tutorial", "tutorial") }.getOrElse { error ->
            ctx.repository.unmarkTutorialBonus(uuid)
            throw error
        }
    }

    /**
     * Pays the Kronen of a rewards document and returns its body.
     *
     * @param uuid player.
     * @param docId rewards document id.
     * @param ref ledger reference.
     * @return the body plus `{paid: true}`.
     */
    fun payDocument(uuid: String, docId: String, ref: String): JsonObject {
        val body = ctx.doc("rewards", docId) ?: JsonObject(emptyMap())
        val kronen = body["kronen"]?.jsonPrimitive?.longOrNull ?: 0
        if (kronen > 0) {
            val money = ctx.kronen.deposit(uuid, kronen, "reward:$docId", ref)
            if (!money.ok) Results.fail(money.code ?: "error", "reward payout failed")
        }
        ctx.log("reward", "", uuid, amount = kronen, ref = ref)
        return body.with("paid" to true, "kronen" to kronen)
    }

    private fun nextDay(current: RewardState?, day: LocalDate): Int {
        val length = ctx.int("reward.streakDays", 7).coerceAtLeast(1)
        val continues = current?.lastDay == day.minusDays(1).toString()
        return if (continues) current!!.streak % length + 1 else 1
    }

    /**
     * Registers the reward and tutorial actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        r.register("citybuild.reward.state", "citybuild.reward.state <uuid>", "Daily reward streak state.", null) { a ->
            Results.ok(state(a.str(0, "uuid")))
        }
        r.register("citybuild.reward.claim", "citybuild.reward.claim <uuid>", "Claims today's reward; returns its body.", null) { a ->
            Results.ok(claim(a.str(0, "uuid")))
        }
        r.register("citybuild.tutorial.state", "citybuild.tutorial.state <uuid>", "Tutorial state {done, bonusPaid}.", null) { a ->
            val state = ctx.repository.tutorial(a.str(0, "uuid"))
            Results.ok(jsonOf("done" to (state?.doneAt != null), "bonusPaid" to (state?.bonusPaidAt != null)))
        }
        r.register("citybuild.tutorial.complete", "citybuild.tutorial.complete <uuid>", "Completes the tutorial; pays the bonus once.", null) { a ->
            Results.ok(completeTutorial(a.str(0, "uuid")))
        }
    }
}
