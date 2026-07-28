package org.helix.node.addons

import org.helix.api.addon.AddonContext
import org.helix.api.addon.HelixAddon

/**
 * Test addon whose [onEnable] always fails, used to verify that a failed
 * enable attempt is reported and does not leak its classloader.
 */
class ThrowingTestAddon : HelixAddon {
    override fun onEnable(context: AddonContext) {
        error("boom")
    }
}
