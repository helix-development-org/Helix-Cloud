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
     * Registers `addon.list`, `addon.list.reload`, `addon.enable` and
     * `addon.disable`. The in-game addon management rides on the unified
     * `/helix` command via [helixSubcommand].
     *
     * @param registry target registry.
     */
    fun registerAll(registry: ActionRegistry) {
        registry.register(
            ActionDescriptor(
                "addon.list.reload",
                "Loads new .hxa files from Helix/addons/ without a restart.",
                "addon.list.reload",
            ),
        ) {
            val added = manager.reload()
            if (added.isEmpty()) {
                ActionResult.ok("no new addons found in Helix/addons/")
            } else {
                ActionResult.ok(
                    "loaded ${added.size} new addon${if (added.size == 1) "" else "s"}:",
                    *added.map { "${it.manifest.id} ${it.manifest.version} [${it.state}]" }.toTypedArray(),
                )
            }
        }
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

    /**
     * Dispatches the addon-management subcommands of the in-game `/helix`
     * command (`addons`, `enable`, `disable`, `reload`).
     *
     * @param args the arguments after the player name.
     * @return the command result.
     */
    fun helixSubcommand(args: List<String>): ActionResult = when (args.firstOrNull()?.lowercase()) {
        "addons", "list" -> {
            val addons = manager.addons()
            if (addons.isEmpty()) {
                ActionResult.ok("&7No addons installed.")
            } else {
                ActionResult.ok(
                    "&bAddons (${addons.size}):",
                    *addons.map { "&7- &f${it.manifest.id} &7${it.manifest.version} &8[${it.state}]" }
                        .toTypedArray(),
                )
            }
        }
        "enable" -> args.getOrNull(1)?.let { id ->
            if (manager.enable(id)) ActionResult.ok("&aEnabled &f$id") else ActionResult.error("&cUnknown addon: $id")
        } ?: ActionResult.error("usage: /helix enable <id>")
        "disable" -> args.getOrNull(1)?.let { id ->
            if (manager.disable(id)) ActionResult.ok("&aDisabled &f$id") else ActionResult.error("&cUnknown addon: $id")
        } ?: ActionResult.error("usage: /helix disable <id>")
        "reload" -> {
            val added = manager.reload()
            if (added.isEmpty()) {
                ActionResult.ok("&7No new addons found in Helix/addons/.")
            } else {
                ActionResult.ok(
                    "&aLoaded ${added.size} new addon${if (added.size == 1) "" else "s"}:",
                    *added.map { "&7- &f${it.manifest.id} &8[${it.state}]" }.toTypedArray(),
                )
            }
        }
        else -> ActionResult.ok(
            "&bHelix commands:",
            "&f/helix addons &7— list installed addons",
            "&f/helix enable <id> &7— enable an addon",
            "&f/helix disable <id> &7— disable an addon",
            "&f/helix reload &7— load new .hxa files",
        )
    }
}
