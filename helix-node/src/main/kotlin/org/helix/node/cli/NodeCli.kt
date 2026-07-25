package org.helix.node.cli

import java.io.BufferedReader
import java.io.PrintStream
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionSource

/**
 * Interactive console of the node.
 *
 * Every input line is `<action> [arguments...]` and routed through the
 * action registry, so the CLI has zero behaviour of its own. `help` lists
 * actions, `exit` triggers `platform.stop`.
 *
 * @property invoker action entry point.
 * @property output stream for results.
 */
class NodeCli(
    private val invoker: ActionInvoker,
    private val output: PrintStream = System.out,
) {
    /**
     * Processes input lines until the stream ends.
     *
     * Read errors are treated like end-of-input: a node that lost its
     * controlling terminal (for example the successor of a backend
     * restart, whose inherited terminal returns `EIO`) must never crash
     * the main thread — the caller decides whether to park or shut down.
     *
     * @param reader source of input lines.
     */
    fun run(reader: BufferedReader) {
        output.println("Helix-Cloud CLI ready — type 'help' for actions.")
        while (true) {
            output.print("helix> ")
            output.flush()
            val line = try {
                reader.readLine() ?: break
            } catch (failure: java.io.IOException) {
                output.println("console input unavailable (${failure.message}) — CLI closed")
                break
            }
            if (!handle(line)) {
                break
            }
        }
    }

    /**
     * Handles one input line.
     *
     * @param line raw console input.
     * @return `false` when the CLI should terminate.
     */
    fun handle(line: String): Boolean {
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            return true
        }
        val action = when (tokens.first().lowercase()) {
            "help" -> "actions.list"
            "exit", "stop" -> "platform.stop"
            else -> tokens.first()
        }
        val result = invoker.invoke(
            ActionInvocation(action = action, arguments = tokens.drop(1), source = ActionSource.CLI),
        )
        result.lines.forEach(output::println)
        if (!result.success && result.lines.isEmpty()) {
            output.println("action failed")
        }
        return action != "platform.stop" || !result.success
    }
}
