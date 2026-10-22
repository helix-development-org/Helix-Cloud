package org.helix.addons.mineclash.war

import org.helix.addons.mineclash.profile.KronenRewards
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.message.Messages
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor

/**
 * Runs the coordinator's side effects outside its lock, in order, on [executor] (a single thread in production,
 * direct in tests): chat messages in the recipient's language, moves to a war server (a failure is reported to
 * the player), war-server starts (`service.start` blocks while the workspace is prepared) and war payouts.
 *
 * @property actions node action invoker.
 * @property messages the addon's localized messages.
 * @property kronen crown payouts.
 * @property executor where effects run.
 */
class WarEffectRunner(
    private val actions: ActionInvoker,
    private val messages: Messages,
    private val kronen: KronenRewards,
    private val executor: Executor,
) {
    private val logger = LoggerFactory.getLogger(WarEffectRunner::class.java)

    /**
     * Queues effects (no-op for an empty list).
     *
     * @param effects the effects, run in order.
     */
    fun run(effects: List<WarEffect>) {
        if (effects.isEmpty()) return
        executor.execute {
            effects.forEach { effect ->
                runCatching { apply(effect) }.onFailure { logger.warn("clan-war effect {} failed: {}", effect, it.message) }
            }
        }
    }

    /**
     * Renders a chat message in a player's language (with the network prefix); keys in [Msg.refs] are inserted
     * as raw text in the same language.
     *
     * @param player the recipient.
     * @param msg the message.
     * @return the MiniMessage text.
     */
    fun render(player: String, msg: Msg): String = messages.formatFor(player, msg.key, *params(player, msg))

    /**
     * Renders a message for a disconnect screen (no chat prefix), for example a refused war-server login.
     *
     * @param player the recipient.
     * @param msg the message.
     * @return the MiniMessage text.
     */
    fun renderScreen(player: String, msg: Msg): String = messages.screenFor(player, msg.key, *params(player, msg))

    private fun params(player: String, msg: Msg): Array<Pair<String, String>> =
        (msg.params + msg.refs.mapValues { (_, key) -> messages.rawFor(player, key) }).toList().toTypedArray()

    private fun apply(effect: WarEffect) {
        when (effect) {
            is WarEffect.Message -> actions.invoke(ActionInvocation("player.message", listOf(effect.player, render(effect.player, effect.msg))))
            is WarEffect.Connect -> {
                val result = actions.invoke(ActionInvocation("player.connect", listOf(effect.player, effect.serviceId)))
                if (!result.success) {
                    logger.info("clan war: connecting {} to {} failed: {}", effect.player, effect.serviceId, result.lines.joinToString(" "))
                    actions.invoke(ActionInvocation("player.message", listOf(effect.player, render(effect.player, Msg("clanwar.join.failed")))))
                }
            }
            is WarEffect.StartService -> {
                val result = actions.invoke(ActionInvocation("service.start", listOf(effect.task)))
                logger.info("clan war: service.start {} → {}", effect.task, result.lines.joinToString(" "))
            }
            is WarEffect.PayWar -> kronen.payWar(effect.warId, effect.players, effect.winner).forEach { (uuid, amount) ->
                val name = effect.names[uuid] ?: return@forEach
                actions.invoke(ActionInvocation("player.message", listOf(name, render(name, Msg("clanwar.result.reward", mapOf("kronen" to amount.toString()))))))
            }
        }
    }
}

/**
 * Finds the running services of the village task (players return there after a war) through `service.list`,
 * refreshed periodically; falls back to `<gameTask>-1`.
 *
 * @property actions node action invoker.
 * @property gameTask supplier of the village task name.
 */
class ServiceDirectory(private val actions: ActionInvoker, private val gameTask: () -> String) {
    @Volatile
    private var running: List<String> = emptyList()

    /** Reads `service.list` (call periodically, off the coordinator lock). */
    fun refresh() {
        val result = runCatching { actions.invoke(ActionInvocation("service.list")) }.getOrNull() ?: return
        if (!result.success) return
        running = parse(result.lines, gameTask())
    }

    /**
     * The service players return to.
     *
     * @return the first running village service, else `<gameTask>-1`.
     */
    fun returnTo(): String = running.firstOrNull() ?: "${gameTask()}-1"

    /** Parser of the `service.list` lines. */
    companion object {
        /**
         * Running service ids of a task from `service.list` lines (`<id> [RUNNING] port=…`).
         *
         * @param lines the lines.
         * @param task the task name.
         * @return matching running ids, in order.
         */
        fun parse(lines: List<String>, task: String): List<String> = lines.mapNotNull { line ->
            val id = line.substringBefore(' ')
            id.takeIf { it.startsWith("$task-", ignoreCase = true) && line.contains("[RUNNING]") }
        }
    }
}
