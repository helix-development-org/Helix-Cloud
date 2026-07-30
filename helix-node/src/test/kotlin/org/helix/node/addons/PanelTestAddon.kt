package org.helix.node.addons

import org.helix.api.addon.AddonContext
import org.helix.api.addon.DashboardPanel
import org.helix.api.addon.HelixAddon

/**
 * Test addon registering a dashboard panel of its own, which must
 * suppress the generated default page.
 */
class PanelTestAddon : HelixAddon {
    override fun onEnable(context: AddonContext) {
        context.registerDashboardPanel(DashboardPanel(id = "custom", title = "Custom", html = "<p>own page</p>"))
    }
}
