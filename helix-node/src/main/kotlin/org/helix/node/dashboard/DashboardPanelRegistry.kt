package org.helix.node.dashboard

import org.helix.api.addon.DashboardPanel
import org.helix.api.addon.DashboardPanelInfo
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of dashboard pages contributed by addons.
 *
 * Panels are keyed by id and tracked per owner so an addon's pages
 * disappear from the dashboard when it is disabled.
 */
class DashboardPanelRegistry {
    private val logger = LoggerFactory.getLogger(DashboardPanelRegistry::class.java)
    private val panels = ConcurrentHashMap<String, Entry>()

    private data class Entry(val owner: String, val panel: DashboardPanel)

    /**
     * Registers a panel under an owner id.
     *
     * A panel id already taken by a different owner is rejected with a log
     * message so two addons cannot silently clobber each other's page.
     *
     * @param owner owning addon id, used for cleanup on disable.
     * @param panel the page to register.
     */
    fun register(owner: String, panel: DashboardPanel) {
        val previous = panels.putIfAbsent(panel.id, Entry(owner, panel))
        if (previous != null && previous.owner != owner) {
            logger.warn("Panel id {} already registered by {}, ignoring {}", panel.id, previous.owner, owner)
            return
        }
        panels[panel.id] = Entry(owner, panel)
    }

    /**
     * Removes all panels of an owner.
     *
     * @param owner the owning addon id.
     */
    fun unregisterOwner(owner: String) {
        panels.entries.removeIf { it.value.owner == owner }
    }

    /**
     * Lists panel metadata for the sidebar.
     *
     * @return panel metadata sorted by title.
     */
    fun list(): List<DashboardPanelInfo> = panels.values
        .map { DashboardPanelInfo(it.panel.id, it.panel.title, it.panel.icon) }
        .sortedBy { it.title }

    /**
     * Looks up a panel's full markup.
     *
     * @param id the panel id.
     * @return the panel or `null`.
     */
    fun find(id: String): DashboardPanel? = panels[id]?.panel
}
