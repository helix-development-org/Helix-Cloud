package org.helix.node.addons

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.api.addon.AddonContext
import org.helix.api.addon.HelixAddon

/**
 * Second test addon registering a distinct action, for reload tests.
 */
class SecondTestAddon : HelixAddon {
    override fun onEnable(context: AddonContext) {
        context.registerAction(ActionDescriptor("test.pong", "pong", "test.pong")) {
            ActionResult.ok("pong")
        }
    }
}
