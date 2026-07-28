package org.helix.node.privacy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.node.actions.ActionRegistry
import org.helix.node.gates.PlayerDataRegistry

/**
 * Registers the GDPR export/delete actions, aggregating every addon's
 * [org.helix.api.addon.PlayerDataProvider] into one request each — the
 * node core knows nothing about bans, warns, permissions, friends, clans
 * or balances specifically.
 *
 * Every invocation is already covered by the generic action audit trail
 * (see [org.helix.node.actions.ActionRegistry.onInvocation]), so both
 * actions are inherently accountable without extra bookkeeping here.
 *
 * @property playerData aggregated player-data providers of all addons.
 */
class PlayerDataActions(private val playerData: PlayerDataRegistry) {
    private val json = Json { prettyPrint = true }

    /**
     * Registers `player.gdpr-export` and `player.gdpr-delete`.
     *
     * @param registry target registry.
     */
    fun registerAll(registry: ActionRegistry) {
        registry.register(
            ActionDescriptor(
                "player.gdpr-export",
                "Exports all data the network holds about a player as one JSON document.",
                "player.gdpr-export <player>",
            ),
        ) { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@register ActionResult.error("usage: player.gdpr-export <player>")
            val sources = playerData.export(player)
            val document = buildJsonObject {
                put("player", JsonPrimitive(player))
                putJsonObject("sources") {
                    sources.forEach { (owner, raw) -> put(owner, raw.toJsonElementOrString()) }
                }
            }
            ActionResult.ok(json.encodeToString(JsonObject.serializer(), document))
        }
        registry.register(
            ActionDescriptor(
                "player.gdpr-delete",
                "Deletes or anonymizes all data the network holds about a player.",
                "player.gdpr-delete <player>",
            ),
        ) { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@register ActionResult.error("usage: player.gdpr-delete <player>")
            val removedFrom = playerData.delete(player)
            if (removedFrom.isEmpty()) {
                ActionResult.ok("no data found for $player")
            } else {
                ActionResult.ok("removed $player's data from: ${removedFrom.joinToString()}")
            }
        }
    }

    /** Parses an addon's raw export as JSON, falling back to a plain string. */
    private fun String.toJsonElementOrString() =
        runCatching { json.parseToJsonElement(this) }.getOrElse { JsonPrimitive(this) }
}
