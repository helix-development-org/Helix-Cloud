package org.helix.api.addon

/**
 * Contract every addon main class implements.
 *
 * Addons are packaged as HXA files (`addon.json` + `addon.jar`), loaded from
 * `Helix/addons/` with an isolated classloader and enabled after the node
 * booted.
 */
interface HelixAddon {
    /**
     * Called once after the addon was loaded.
     *
     * @param context node facilities scoped to this addon.
     */
    fun onEnable(context: AddonContext)

    /**
     * Called once before the addon is unloaded. Optional.
     */
    fun onDisable() {
    }
}
