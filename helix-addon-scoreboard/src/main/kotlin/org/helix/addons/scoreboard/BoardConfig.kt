package org.helix.addons.scoreboard

import kotlinx.serialization.Serializable

/**
 * Persisted configuration of a single sidebar scoreboard.
 *
 * One board belongs to a task (or the shared `default` entry used when a
 * task has no board of its own). The paper bridge renders [title] as the
 * sidebar objective title and each of [lines] as a sidebar row, both as
 * MiniMessage/`&`-coloured strings with `{placeholder}` markers substituted
 * per player.
 *
 * @property title sidebar title, MiniMessage or `&` color codes.
 * @property lines ordered sidebar rows, top to bottom, at most
 *   [ScoreboardAddon.MAX_LINES] entries.
 * @property enabled whether the board is shown at all.
 * @property updateIntervalTicks server ticks between refreshes (keeps live
 *   placeholders such as `{ping}` or `{tps}` up to date).
 */
@Serializable
data class BoardConfig(
    val title: String = "&6&lHELIX-CLOUD",
    val lines: List<String> = listOf(
        "&7&m                    ",
        "&fPlayer: &e{player}",
        "&fServer: &a{server}",
        "&fOnline: &a{online}",
        "&7&m                    ",
        "&eplay.helix-cloud.net",
    ),
    val enabled: Boolean = true,
    val updateIntervalTicks: Int = 20,
)
