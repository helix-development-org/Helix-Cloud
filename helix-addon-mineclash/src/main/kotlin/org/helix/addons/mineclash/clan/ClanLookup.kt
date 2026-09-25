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
 * Resolves clan membership through the `helix.clan` addon's actions (`clan.list`, `clan.detail`),
 * cached for [cacheMillis]. MineClash owns no clan data of its own.
 *
 * @property actions node action invoker.
 * @property clock time source.
 * @property cacheMillis cache lifetime.
 */
class ClanLookup(
    private val actions: ActionInvoker,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheMillis: Long = 30_000,
) {
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
}
