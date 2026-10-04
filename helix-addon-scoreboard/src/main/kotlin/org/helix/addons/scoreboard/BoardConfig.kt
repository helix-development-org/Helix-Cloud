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
    // Design concept §10: title = wordmark (gold network name until the glyph exists), labels wood-light,
    // values parchment, separators are empty lines.
    val title: String = "<#ffcf4a><bold>{network}</bold>",
    val lines: List<String> = listOf(
        "",
        "<#7a4a24>Player » <#f1e2c2>{player}",
        "<#7a4a24>Server » <#f1e2c2>{server}",
        "<#7a4a24>Online » <#f1e2c2>{online}",
        "",
    ),
    val enabled: Boolean = true,
    val updateIntervalTicks: Int = 20,
)
