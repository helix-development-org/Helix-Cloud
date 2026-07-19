package org.helix.node.addons

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.node.actions.ActionRegistry

/**
 * Registers the addon management actions.
 *
 * @property manager the addon manager.
 */
class AddonActions(private val manager: AddonManager) {
    /**
     * Registers `addon.list`, `addon.enable` and `addon.disable`.
     *
     * @param registry target registry.
     */
    fun registerAll(registry: ActionRegistry) {
        registry.register(
            ActionDescriptor("addon.list", "Lists installed addons.", "addon.list"),
        ) {
            val addons = manager.addons()
            if (addons.isEmpty()) {
                ActionResult.ok("no addons installed — drop .hxa files into Helix/addons/")
            } else {
                ActionResult.ok(
                    *addons.map { addon ->
                        val manifest = addon.manifest
                        "${manifest.id} ${manifest.version} [${addon.state}] — ${manifest.name}"
                    }.toTypedArray(),
                )
            }
        }
        registry.register(
            ActionDescriptor("addon.enable", "Enables a disabled addon.", "addon.enable <id>"),
        ) { invocation ->
            val id = invocation.arguments.firstOrNull()
                ?: return@register ActionResult.error("usage: addon.enable <id>")
            if (manager.enable(id)) ActionResult.ok("enabled $id") else ActionResult.error("unknown addon: $id")
        }
        registry.register(
            ActionDescriptor("addon.disable", "Disables an addon and removes its actions.", "addon.disable <id>"),
        ) { invocation ->
            val id = invocation.arguments.firstOrNull()
                ?: return@register ActionResult.error("usage: addon.disable <id>")
            if (manager.disable(id)) ActionResult.ok("disabled $id") else ActionResult.error("unknown addon: $id")
        }
    }
}
