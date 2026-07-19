package org.helix.addon.example

import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.addon.sdk.AddonBase

/**
 * Reference addon proving the addon/action contract.
 *
 * Registers `example.ping` and `example.overview`, the latter showing that
 * addons can call any platform action through their context.
 */
class ExampleAddon : AddonBase() {
    /**
     * Registers the example actions.
     */
    override fun enable() {
        action("example.ping", "Replies with pong.") { invocation ->
            ActionResult.ok("pong" + invocation.arguments.joinToString("") { " $it" })
        }
        action("example.overview", "Shows the platform overview through the addon context.") {
            context.actions.invoke(
                ActionInvocation("platform.overview", source = ActionSource.ADDON),
            )
        }
    }
}
