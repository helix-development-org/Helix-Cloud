package org.helix.addons.mineclash.clan

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker

/**
 * Clan membership of one player as seen by MineClash.
 *
 * @property id clan id.
 * @property name clan name.
 * @property tag clan tag.
 * @property role the player's role.
 * @property members member names.
 */
@Serializable
data class ClanMembership(val id: String, val name: String, val tag: String, val role: String, val members: List<String>)

/**
 * A clan with its members' roles, read fresh from the clan addon.
 *
 * @property id clan id (lower-case clan name).
 * @property name clan name.
 * @property tag clan tag.
 * @property members lower-case member name to role (`OWNER`, `OFFICER`, `MEMBER`).
 */
data class ClanView(val id: String, val name: String, val tag: String, val members: Map<String, String>) {
    /**
     * Whether a player may declare, accept or cancel clan wars (owner or officer).
     *
     * @param player player name (any case).
     * @return true for owners and officers of this clan.
     */
    fun isOfficer(player: String): Boolean = members[player.lowercase()]?.let { it == "OWNER" || it == "OFFICER" || it == "LEADER" } == true

    /**
     * Whether a player belongs to this clan.
     *
     * @param player player name (any case).
     * @return true for members of any role.
     */
    fun has(player: String): Boolean = player.lowercase() in members
}

/** Outcome of reading a clan: found, known not to exist, or the clan addon did not answer properly. */
sealed interface ClanRead {
    /**
     * The clan exists.
     *
     * @property view the clan.
     */
    data class Found(val view: ClanView) : ClanRead

    /** The clan addon answered that there is no such clan (disbanded or never existed). */
    data object Missing : ClanRead

    /** No usable answer (addon not installed, exception, unreadable reply) – says nothing about the clan. */
    data object Failed : ClanRead
}

/** Clan data the clan-war coordinator needs (implemented by [ClanLookup], faked in tests). */
interface ClanSource {
    /**
     * The clan id of a player (may be cached for a short while).
     *
     * @param player player name (any case).
     * @return the clan id or null when clanless.
     */
    fun clanIdOf(player: String): String?

    /**
     * A clan read fresh from the clan addon (never cached: rank and membership decisions use it).
     *
     * @param token clan id, tag or name.
     * @return the clan or null when it does not exist.
     */
    fun detail(token: String): ClanView?

    /**
     * Whether the clan addon answers at all; a missing clan only means "disbanded" when it does.
     *
     * @return true when clans can be read.
     */
    fun reachable(): Boolean = true

    /**
     * Reads a clan and tells "does not exist" apart from "could not be read" (membership checks must not end a war
     * because the clan addon hiccuped).
     *
     * @param token clan id, tag or name.
     * @return the outcome; the default maps a null [detail] to [ClanRead.Missing].
     */
    fun read(token: String): ClanRead = detail(token)?.let { ClanRead.Found(it) } ?: ClanRead.Missing
}

/**
 * Resolves clan membership through the `helix.clan` addon's actions (`clan.list`, `clan.detail`),
 * cached for [cacheMillis]. MineClash owns no clan data of its own. [detail] bypasses the cache.
 *
 * @property actions node action invoker.
 * @property clock time source.
 * @property cacheMillis cache lifetime.
 */
class ClanLookup(
    private val actions: ActionInvoker,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheMillis: Long = 30_000,
) : ClanSource {
    private val json = Json { ignoreUnknownKeys = true }
    private var cachedAt = 0L
    private var byMember: Map<String, ClanMembership> = emptyMap()

    /**
     * Returns the clan of a player.
     *
     * @param player player name (any case).
     * @return the membership or `null` when clanless or the clan addon is unavailable.
     */
    @Synchronized
    fun clanOf(player: String): ClanMembership? {
        refreshIfStale()
        return byMember[player.lowercase()]
    }

    /** Drops the cache so the next lookup queries the clan addon again. */
    @Synchronized
    fun invalidate() {
        cachedAt = 0
    }

    /**
     * The clan id of a player; on a cache miss the cache is refreshed once, so a player who just joined a clan
     * is found without waiting for the cache to expire.
     *
     * @param player player name (any case).
     * @return the clan id or null when clanless.
     */
    @Synchronized
    override fun clanIdOf(player: String): String? {
        clanOf(player)?.let { return it.id }
        if (clock() - cachedAt < MISS_REFRESH_MILLIS) return null
        cachedAt = 0
        return clanOf(player)?.id
    }

    /**
     * Whether `clan.list` answers (the clan addon is installed and running).
     *
     * @return true when clans can be read.
     */
    override fun reachable(): Boolean = runCatching { actions.invoke(ActionInvocation("clan.list")).success }.getOrDefault(false)

    /**
     * Reads a clan fresh through `clan.detail` (id, tag or name).
     *
     * @param token clan id, tag or name.
     * @return the clan or null when it does not exist or the clan addon is unavailable.
     */
    override fun detail(token: String): ClanView? = (read(token) as? ClanRead.Found)?.view

    /**
     * Reads a clan through `clan.detail`: the clan addon's "no such clan" answer is [ClanRead.Missing], every other
     * failure (unknown action, exception, unreadable JSON) is [ClanRead.Failed].
     *
     * @param token clan id, tag or name.
     * @return the outcome.
     */
    override fun read(token: String): ClanRead {
        val result = runCatching { actions.invoke(ActionInvocation("clan.detail", listOf(token))) }.getOrNull() ?: return ClanRead.Failed
        if (!result.success) {
            return if (result.lines.any { it.contains("no such clan", ignoreCase = true) }) ClanRead.Missing else ClanRead.Failed
        }
        val view = parse(result.lines.firstOrNull()) ?: return ClanRead.Failed
        return ClanRead.Found(view)
    }

    private fun parse(line: String?): ClanView? {
        if (line == null) return null
        return runCatching {
            val obj = json.parseToJsonElement(line).jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            ClanView(
                id = id,
                name = obj["name"]?.jsonPrimitive?.content ?: id,
                tag = obj["tag"]?.jsonPrimitive?.content ?: "",
                members = obj.getValue("members").jsonArray.associate { entry ->
                    val member = entry.jsonObject
                    member.getValue("name").jsonPrimitive.content.lowercase() to (member["role"]?.jsonPrimitive?.content ?: "MEMBER")
                },
            )
        }.getOrNull()
    }

    private fun refreshIfStale() {
        if (clock() - cachedAt < cacheMillis) return
        cachedAt = clock()
        val list = actions.invoke(ActionInvocation("clan.list"))
        if (!list.success) return
        val ids = runCatching { json.parseToJsonElement(list.lines.first()).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content } }
            .getOrElse { return }
        val members = mutableMapOf<String, ClanMembership>()
        for (id in ids) {
            val detail = actions.invoke(ActionInvocation("clan.detail", listOf(id)))
            if (!detail.success) continue
            runCatching {
                val obj = json.parseToJsonElement(detail.lines.first()).jsonObject
                val entries = obj.getValue("members").jsonArray.map { it.jsonObject }
                val names = entries.map { it.getValue("name").jsonPrimitive.content }
                entries.forEach { entry ->
                    val name = entry.getValue("name").jsonPrimitive.content
                    members[name.lowercase()] = ClanMembership(
                        id = id,
                        name = obj["name"]?.jsonPrimitive?.content ?: id,
                        tag = obj["tag"]?.jsonPrimitive?.content ?: "",
                        role = entry["role"]?.jsonPrimitive?.content ?: "MEMBER",
                        members = names,
                    )
                }
            }
        }
        byMember = members
    }

    private companion object {
        /** A cache miss refreshes at most this often (a clanless player spamming commands must not hammer the clan addon). */
        const val MISS_REFRESH_MILLIS = 5_000L
    }
}
