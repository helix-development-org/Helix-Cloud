package org.helix.node.release

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.api.release.ReleaseReset
import org.helix.node.actions.ActionRegistry

/**
 * Registers `release.reset` and `release.status` (permission [ReleaseReset.PERMISSION]).
 *
 * @property service the release reset orchestrator.
 */
class ReleaseActions(private val service: ReleaseResetService) {
    /**
     * Registers the actions.
     *
     * @param registry target registry.
     */
    fun registerAll(registry: ActionRegistry) {
        registry.register(
            ActionDescriptor(
                RESET,
                "Network release reset: 'plan' shows what happens, 'run <phrase>' resets player data and worlds.",
                "release.reset <plan|run <phrase> [--remove-testers] [--keep-maintenance]>",
                permission = ReleaseReset.PERMISSION,
            ),
        ) { invocation ->
            when (invocation.arguments.firstOrNull()?.lowercase()) {
                ReleaseReset.PLAN -> ActionResult.ok(*service.plan().toTypedArray())
                ReleaseReset.RUN -> service.run(
                    phrase = invocation.arguments.getOrNull(1),
                    removeTesters = ReleaseReset.FLAG_REMOVE_TESTERS in invocation.arguments,
                    keepMaintenance = FLAG_KEEP_MAINTENANCE in invocation.arguments,
                )
                else -> ActionResult.error("usage", "release.reset <plan|run <phrase> [--remove-testers] [--keep-maintenance]>")
            }
        }
        registry.register(
            ActionDescriptor(STATUS, "Shows the progress of the last release reset.", STATUS, permission = ReleaseReset.PERMISSION),
        ) { _ ->
            val status = service.status()
            ActionResult.ok(
                "state: ${status.state}",
                *status.steps.toTypedArray(),
                *status.errors.map { "error: $it" }.toTypedArray(),
            )
        }
    }

    /** Action names and flags. */
    companion object {
        /** Orchestrator action (excluded from the participant discovery). */
        const val RESET: String = "release.reset"

        /** Status action. */
        const val STATUS: String = "release.status"

        /** Leave maintenance on after the reset. */
        const val FLAG_KEEP_MAINTENANCE: String = "--keep-maintenance"
    }
}
