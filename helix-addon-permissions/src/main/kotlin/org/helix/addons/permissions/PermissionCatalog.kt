package org.helix.addons.permissions

import org.helix.api.addon.AddonContext

/**
 * Aggregates every known permission node in the network: the platform's core
 * permissions, the nodes each installed addon declares in its `addon.json`,
 * and the nodes backend plugins declare in their `plugin.yml`. The catalog
 * powers the selectable permission picker in the permissions panel; unknown
 * nodes can still be entered freely.
 *
 * Results are cached briefly since plugin scanning touches jar files.
 *
 * @property context addon context providing addons, core nodes and scan roots.
 * @property clock epoch-millis source, injectable for tests.
 */
class PermissionCatalog(
    private val context: AddonContext,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scanner = PluginPermissionScanner { context.serviceDirectories() }
    private var cached: List<CatalogEntry> = emptyList()
    private var cachedAtMs: Long = 0

    /**
     * All known permission nodes with their sources, first occurrence wins.
     *
     * @return catalog entries sorted by node.
     */
    @Synchronized
    fun entries(): List<CatalogEntry> {
        val now = clock()
        if (now - cachedAtMs < CACHE_MS && cached.isNotEmpty()) {
            return cached
        }
        val bySource = linkedMapOf<String, CatalogEntry>()
        context.installedAddons().forEach { addon ->
            addon.manifest.permissions.forEach { node ->
                bySource.putIfAbsent(node, CatalogEntry(node, "addon:${addon.manifest.id}"))
            }
        }
        context.corePermissions().forEach { node ->
            bySource.putIfAbsent(node, CatalogEntry(node, "core"))
        }
        scanner.scan().forEach { (plugin, nodes) ->
            nodes.forEach { node ->
                bySource.putIfAbsent(node, CatalogEntry(node, "plugin:$plugin"))
            }
        }
        cached = bySource.values.sortedBy { it.node }
        cachedAtMs = now
        return cached
    }

    private companion object {
        /** Milliseconds a catalog scan stays cached. */
        const val CACHE_MS = 30_000L
    }
}
