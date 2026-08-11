package org.helix.bridge.paper

/**
 * Substitutes sidebar scoreboard placeholders in two passes.
 *
 * [Global] values are identical for every viewer during one refresh tick
 * (online count, tps, clock, ...); resolving and substituting them once per
 * tick — rather than once per line per online player — avoids redundant
 * work as the player count grows. [PerPlayer] values are then substituted
 * on top, per viewer, on the already globally-substituted text.
 */
object ScoreboardPlaceholders {
    /** Board placeholder values shared by every viewer during one refresh tick. */
    data class Global(
        val online: Int,
        val max: Int,
        val server: String,
        val task: String,
        val tps: String,
        val date: String,
        val time: String,
        val network: String,
        val prefix: String,
    )

    /** Board placeholder values specific to a single viewer. */
    data class PerPlayer(
        val name: String,
        val displayName: String,
        val nick: String,
        val ping: Int,
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val balance: String,
        val clan: String,
    )

    /**
     * Substitutes the placeholders shared by every viewer.
     *
     * @param text raw board title or line.
     * @param values this tick's global values.
     * @return [text] with global placeholders substituted.
     */
    fun global(text: String, values: Global): String = text
        .replace("{online}", values.online.toString())
        .replace("{max}", values.max.toString())
        .replace("{server}", values.server)
        .replace("{task}", values.task)
        .replace("{tps}", values.tps)
        .replace("{date}", values.date)
        .replace("{time}", values.time)
        .replace("{network}", values.network)
        .replace("{prefix}", values.prefix)

    /**
     * Substitutes the placeholders specific to one viewer.
     *
     * @param text board title or line, global placeholders already substituted.
     * @param values the viewer's values.
     * @return [text] with the remaining per-player placeholders substituted.
     */
    fun player(text: String, values: PerPlayer): String = text
        .replace("{player}", values.name)
        .replace("{displayname}", values.displayName)
        .replace("{nick}", values.nick)
        .replace("{ping}", values.ping.toString())
        .replace("{world}", values.world)
        .replace("{x}", values.x.toString())
        .replace("{y}", values.y.toString())
        .replace("{z}", values.z.toString())
        .replace("{balance}", values.balance)
        .replace("{clan}", values.clan)
}
