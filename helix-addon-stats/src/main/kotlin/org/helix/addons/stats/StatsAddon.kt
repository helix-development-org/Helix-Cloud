package org.helix.addons.stats

import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult

/**
 * Generic stats/leaderboards addon.
 *
 * Exposes a small action API (`stats.add`, `stats.set`, `stats.get`,
 * `stats.top`, `stats.list`) so any addon or external Paper plugin can feed
 * numeric per-player stats — kills, playtime, whatever an operator wants to
 * track — through the control API, with stat names as free-form keys
 * instead of a hardcoded enum. `stats.season.*` adds archive-then-clear
 * seasonal resets on top: an operator wires `stats.season.reset <stat>` into
 * a job (`POST /jobs`, `everyMinutes` or `dailyAt`) for a periodic reset,
 * and past standings stay viewable through `stats.season.list`/
 * `stats.season.view`. The in-game `/stats` command lets players check
 * their own value and browse leaderboards.
 */
class StatsAddon : AddonBase() {
    private lateinit var store: StatsStore
    private lateinit var seasons: SeasonStore
    private lateinit var msg: org.helix.api.message.Messages

    /**
     * Registers the `stats.*` actions, the `/stats` player command and the
     * seasonal archive actions.
     */
    override fun enable() {
        store = StatsStore(context.storage())
        seasons = SeasonStore(context.storage(), store)
        msg = context.localizedMessages(
            mapOf(
                "en" to mapOf(
                    "value" to "&6{stat}: &f{value}",
                    "value.other" to "&6{player}'s {stat}: &f{value}",
                    "top.empty" to "&7No values recorded yet for {stat}.",
                    "top.entry" to "&6#{rank} &f{player} &7— {value}",
                    "usage" to "Usage: /stats <stat> [player] | /stats top <stat> [limit]",
                ),
                "de" to mapOf(
                    "value" to "&6{stat}: &f{value}",
                    "value.other" to "&6{player}s {stat}: &f{value}",
                    "top.empty" to "&7Für {stat} sind noch keine Werte erfasst.",
                    "top.entry" to "&6#{rank} &f{player} &7— {value}",
                    "usage" to "Verwendung: /stats <stat> [player] | /stats top <stat> [limit]",
                ),
            ),
        )

        action("stats.add", "Adds a delta to a player's stat.", "stats.add <stat> <player> <delta>") { inv ->
            val stat = inv.arguments.getOrNull(0)
            val player = inv.arguments.getOrNull(1)
            val delta = inv.arguments.getOrNull(2)?.toLongOrNull()
            if (stat == null || player == null || delta == null) {
                return@action ActionResult.error("usage: stats.add <stat> <player> <delta>")
            }
            ActionResult.ok(store.add(stat, player, delta).toString())
        }
        action("stats.set", "Sets a player's stat to an absolute value.", "stats.set <stat> <player> <value>") { inv ->
            val stat = inv.arguments.getOrNull(0)
            val player = inv.arguments.getOrNull(1)
            val value = inv.arguments.getOrNull(2)?.toLongOrNull()
            if (stat == null || player == null || value == null) {
                return@action ActionResult.error("usage: stats.set <stat> <player> <value>")
            }
            store.set(stat, player, value)
            ActionResult.ok(value.toString())
        }
        action("stats.get", "Reads a player's stat value.", "stats.get <stat> <player>") { inv ->
            val stat = inv.arguments.getOrNull(0)
            val player = inv.arguments.getOrNull(1)
            if (stat == null || player == null) {
                return@action ActionResult.error("usage: stats.get <stat> <player>")
            }
            ActionResult.ok(store.get(stat, player).toString())
        }
        action("stats.top", "Returns the top players for a stat.", "stats.top <stat> [limit]") { inv ->
            val stat = inv.arguments.getOrNull(0)
                ?: return@action ActionResult.error("usage: stats.top <stat> [limit]")
            val limit = inv.arguments.getOrNull(1)?.toIntOrNull() ?: DEFAULT_TOP
            ActionResult.ok(*formatTop(store.top(stat, limit)))
        }
        action("stats.list", "Lists all stat keys with recorded values.", "stats.list") {
            ActionResult.ok(*store.statKeys().toTypedArray())
        }
        action(
            "stats.season.reset",
            "Archives the current standings and clears a stat for a new season.",
            "stats.season.reset <stat>",
        ) { inv ->
            val stat = inv.arguments.getOrNull(0)
                ?: return@action ActionResult.error("usage: stats.season.reset <stat>")
            val record = seasons.reset(stat, System.currentTimeMillis())
                ?: return@action ActionResult.error("no values recorded for $stat")
            ActionResult.ok("archived season ${record.season} for $stat (${record.standings.size} players)")
        }
        action("stats.season.list", "Lists archived seasons for a stat.", "stats.season.list <stat>") { inv ->
            val stat = inv.arguments.getOrNull(0)
                ?: return@action ActionResult.error("usage: stats.season.list <stat>")
            ActionResult.ok(
                *seasons.seasons(stat)
                    .map { "season ${it.season}: ${it.standings.size} players, ended ${it.endedAtEpochMs}" }
                    .toTypedArray(),
            )
        }
        action(
            "stats.season.view",
            "Shows a past season's final leaderboard.",
            "stats.season.view <stat> <season>",
        ) { inv ->
            val stat = inv.arguments.getOrNull(0)
            val season = inv.arguments.getOrNull(1)?.toIntOrNull()
            if (stat == null || season == null) {
                return@action ActionResult.error("usage: stats.season.view <stat> <season>")
            }
            val record = seasons.season(stat, season)
                ?: return@action ActionResult.error("unknown season $season for $stat")
            ActionResult.ok(*formatTop(record.standings.map { it.player to it.value }))
        }
        action(
            "stats",
            "Shows your stat value or a leaderboard.",
            "stats <stat> [player] | stats top <stat> [limit]",
            playerCommand = true,
        ) { handleStatsCommand(it) }
    }

    private fun handleStatsCommand(invocation: ActionInvocation): ActionResult {
        val executor = invocation.arguments.firstOrNull()
            ?: return ActionResult.error("missing executing player")
        val args = invocation.arguments.drop(1)
        if (args.isEmpty()) {
            return ActionResult.error(msg.formatFor(executor, "usage"))
        }
        if (args[0].equals("top", ignoreCase = true)) {
            val stat = args.getOrNull(1) ?: return ActionResult.error(msg.formatFor(executor, "usage"))
            val limit = args.getOrNull(2)?.toIntOrNull() ?: DEFAULT_TOP
            val top = store.top(stat, limit)
            return if (top.isEmpty()) {
                ActionResult.ok(msg.formatFor(executor, "top.empty", "stat" to stat))
            } else {
                ActionResult.ok(
                    *top.mapIndexed { index, (player, value) ->
                        msg.formatFor(
                            executor,
                            "top.entry",
                            "rank" to (index + 1).toString(),
                            "player" to player,
                            "value" to value.toString(),
                        )
                    }.toTypedArray(),
                )
            }
        }
        val stat = args[0]
        val target = args.getOrNull(1)
        val value = store.get(stat, target ?: executor)
        return ActionResult.ok(
            if (target == null) {
                msg.formatFor(executor, "value", "stat" to stat, "value" to value.toString())
            } else {
                msg.formatFor(executor, "value.other", "player" to target, "stat" to stat, "value" to value.toString())
            },
        )
    }

    private fun formatTop(entries: List<Pair<String, Long>>): Array<String> =
        entries.mapIndexed { index, (player, value) -> "#${index + 1} $player - $value" }.toTypedArray()

    private companion object {
        /** Default leaderboard size when no limit argument is given. */
        const val DEFAULT_TOP = 10
    }
}
