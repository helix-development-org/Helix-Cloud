package org.helix.addons.citybuild.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addons.citybuild.config.ConfigSeeds
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.addon.PlayerDataProvider
import org.helix.api.addon.ProfileInfoEntry
import org.helix.api.addon.ProfileInfoProvider

/**
 * Player-centric extras: audit log access, per-instance profile reports (plot count…), arena stats
 * forwarded to `helix.stats`, the profile info lines and GDPR export/delete.
 *
 * @property ctx shared dependencies.
 * @property actions node action invoker (stats addon).
 * @property resolveUuid player name to uuid.
 * @property nameOf uuid to last known name.
 * @property label localized profile label for a player and key.
 */
class ProfileService(
    private val ctx: CityContext,
    private val actions: ActionInvoker,
    private val resolveUuid: (String) -> String?,
    private val nameOf: (String) -> String?,
    private val label: (String, String) -> String,
) {
    /**
     * Forwards arena kills and deaths to the stats addon (`citybuild.arena.kills`/`.deaths`).
     *
     * @param uuid player.
     * @param kills kills to add.
     * @param deaths deaths to add.
     * @return number of accepted stat updates.
     */
    fun recordArena(uuid: String, kills: Int, deaths: Int): Int {
        val name = nameOf(uuid) ?: return 0
        return listOf(KILLS to kills, DEATHS to deaths).filter { it.second > 0 }.count { (stat, delta) ->
            runCatching { actions.invoke(ActionInvocation("stats.add", listOf(stat, name, delta.toString()))).success }.getOrDefault(false)
        }
    }

    /** Profile lines: job levels, reward streak and plots over all instances. */
    val infoProvider: ProfileInfoProvider = object : ProfileInfoProvider {
        override fun infoFor(player: String): List<ProfileInfoEntry> {
            val uuid = resolveUuid(player) ?: return emptyList()
            val jobs = ctx.repository.jobProfile(uuid)
            val streak = ctx.repository.reward(uuid)?.streak ?: 0
            val plots = ctx.repository.profileReports(uuid).values.sumOf { report ->
                runCatching { CityContext.json.parseToJsonElement(report).jsonObject["plots"]?.jsonPrimitive?.intOrNull ?: 0 }.getOrDefault(0)
            }
            return buildList {
                if (plots > 0) add(ProfileInfoEntry(label(player, "profile.plots"), plots.toString()))
                jobs?.active?.filterNotNull()?.forEach { job ->
                    val name = ctx.doc("jobs", job)?.string("name") ?: job
                    add(ProfileInfoEntry(label(player, "profile.job").replace("{job}", name), (jobs.jobs[job]?.level ?: 1).toString()))
                }
                if (streak > 0) add(ProfileInfoEntry(label(player, "profile.streak"), streak.toString()))
            }
        }
    }

    /** GDPR export and deletion of all CityBuild data of a player. */
    val dataProvider: PlayerDataProvider = object : PlayerDataProvider {
        override fun export(player: String): String? {
            val uuid = resolveUuid(player) ?: player
            val shards = instances()
            val json = CityContext.json
            val data = jsonOf(
                "homes" to JsonArray(shards.flatMap { ctx.repository.homes(uuid, it) }.map { json.encodeToJsonElement(it) }),
                "kits" to shards.associateWith { ctx.repository.kitClaims(uuid, it) },
                "pickup" to JsonArray(shards.flatMap { ctx.repository.pickups(uuid, it) }.map { json.encodeToJsonElement(it) }),
                "jobs" to ctx.repository.jobProfile(uuid)?.let { json.encodeToJsonElement(it) },
                "reward" to ctx.repository.reward(uuid)?.let { json.encodeToJsonElement(it) },
                "tutorial" to ctx.repository.tutorial(uuid)?.let { json.encodeToJsonElement(it) },
                "reports" to ctx.repository.profileReports(uuid),
            )
            val hasData = data.values.any { value -> value is JsonArray && value.isNotEmpty() || value is JsonObject && value.isNotEmpty() && value.values.any { it !is JsonObject || it.isNotEmpty() } } ||
                listOf("jobs", "reward", "tutorial").any { data[it] !is kotlinx.serialization.json.JsonNull }
            return if (hasData) data.toString() else null
        }

        override fun delete(player: String): Boolean = ctx.repository.deletePlayer(resolveUuid(player) ?: player)
    }

    private fun instances(): List<String> =
        ctx.doc("servers", ConfigSeeds.TASK)?.get("instances")?.jsonArray?.map { it.jsonPrimitive.content } ?: ConfigSeeds.INSTANCES

    /**
     * Registers the log, profile and arena actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        val json = CityContext.json
        r.register("citybuild.log.write", "citybuild.log.write <kind> <shard> <actorUuid> <json {target, amount, ref, data}>", "Appends an audit entry (shop, trade …).", null) { a ->
            val body = if (a.values.size > 3) a.json(3) else JsonObject(emptyMap())
            ctx.log(
                a.str(0, "kind"), a.str(1, "shard"), a.str(2, "actorUuid"), body.string("target").orEmpty(),
                body["amount"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0, body.string("ref").orEmpty(), body["data"] ?: JsonObject(emptyMap()),
            )
            Results.ok(jsonOf("logged" to true))
        }
        r.register("citybuild.log.query", "citybuild.log.query <json {actor?, ref?, kind?, limit?}>", "Searches the audit log.", "citybuild.admin.logs") { a ->
            val query = if (a.values.isNotEmpty()) a.json(0) else JsonObject(emptyMap())
            val limit = (query["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 500)
            Results.ok(json.encodeToJsonElement(ctx.repository.logs(query.string("actor"), query.string("ref"), query.string("kind"), limit)))
        }
        r.register("citybuild.profile.report", "citybuild.profile.report <uuid> <shard> <json {plots,…}>", "Stores an instance's profile summary of a player.", null) { a ->
            ctx.repository.putProfileReport(a.str(0, "uuid"), a.str(1, "shard"), a.json(2).toString())
            Results.ok(jsonOf("stored" to true))
        }
        r.register("citybuild.arena.top", "citybuild.arena.top [limit 1–20]", "Arena leaderboard (kills) from the network stats.", null) { a ->
            val limit = (a.opt(0)?.toIntOrNull() ?: DEFAULT_TOP).coerceIn(1, MAX_TOP)
            Results.ok(json.encodeToJsonElement(arenaTop(limit)))
        }
        r.register("citybuild.arena.record", "citybuild.arena.record <uuid> <kills> <deaths>", "Adds arena kills/deaths to the network stats.", null) { a ->
            Results.ok(jsonOf("accepted" to recordArena(a.str(0, "uuid"), a.int(1, "kills"), a.int(2, "deaths"))))
        }
    }

    /**
     * Reads the arena leaderboard (kills) from the stats addon (`stats.top`).
     *
     * @param limit number of entries.
     * @return ranked entries; empty when the stats addon is missing or has no values.
     */
    fun arenaTop(limit: Int): List<ArenaRank> {
        val result = runCatching { actions.invoke(ActionInvocation("stats.top", listOf(KILLS, limit.toString()))) }.getOrNull()
        if (result == null || !result.success) return emptyList()
        return parseTop(result.lines)
    }

    /** Stat keys and leaderboard parsing. */
    companion object {
        /** Default leaderboard size. */
        const val DEFAULT_TOP: Int = 10

        /** Largest leaderboard size. */
        const val MAX_TOP: Int = 20

        private val TOP_LINE = Regex("""^#(\d+) (.+) - (-?\d+)$""")

        /**
         * Parses the `stats.top` lines (`#1 Steve - 42`).
         *
         * @param lines result lines of `stats.top`.
         * @return parsed entries; malformed lines are skipped.
         */
        fun parseTop(lines: List<String>): List<ArenaRank> = lines.mapNotNull { line ->
            TOP_LINE.matchEntire(line.trim())?.destructured?.let { (rank, name, kills) -> ArenaRank(rank.toInt(), name, kills.toLong()) }
        }

        /** Arena kills. */
        const val KILLS: String = "citybuild.arena.kills"

        /** Arena deaths. */
        const val DEATHS: String = "citybuild.arena.deaths"
    }
}

/**
 * One arena leaderboard row.
 *
 * @property rank position, starting at 1.
 * @property name player name.
 * @property kills arena kills.
 */
@kotlinx.serialization.Serializable
data class ArenaRank(val rank: Int, val name: String, val kills: Long)
