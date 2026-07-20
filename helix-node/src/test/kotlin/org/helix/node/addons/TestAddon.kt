package org.helix.node.addons

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.api.addon.AddonContext
import org.helix.api.addon.HelixAddon

/**
 * Test addon resolved through the parent classloader.
 */
class TestAddon : HelixAddon {
    override fun onEnable(context: AddonContext) {
        context.registerAction(ActionDescriptor("test.ping", "ping", "test.ping")) {
            ActionResult.ok("pong from ${context.dataDirectory.fileName}")
        }
    }
}
