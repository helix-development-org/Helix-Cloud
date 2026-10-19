package org.helix.addons.feedback.paper

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.inventory.ItemStack

/**
 * An extra entry a game mode plugin adds to `/testtools` (e.g. „MineClash-Testtruhe“ or „Eigenen Plot
 * zurücksetzen“).
 *
 * @property id the entry id, handed back in [TestToolUseEvent].
 * @property label the shown label (already in the player's language).
 * @property icon the row icon.
 */
data class TestToolEntry(val id: String, val label: String, val icon: ItemStack)

/**
 * Fired on the main thread when `/testtools` opens: game mode plugins (MineClash, CityBuild) listen for it and add
 * their own entries to [entries]. Clicking such an entry fires [TestToolUseEvent].
 *
 * Example (CityBuild): `@EventHandler fun on(e: TestToolEvent) { e.entries += TestToolEntry("citybuild.plotreset", "Plot zurücksetzen", ItemStack(Material.GRASS_BLOCK)) }`
 *
 * @property player the tester opening the menu.
 * @property entries entries added by listeners, shown below the built-in tools.
 */
class TestToolEvent(val player: Player, val entries: MutableList<TestToolEntry> = mutableListOf()) : Event() {
    /** Bukkit handler list. */
    override fun getHandlers(): HandlerList = HANDLERS

    /** Bukkit handler list. */
    companion object {
        private val HANDLERS = HandlerList()

        /** @return the handler list. */
        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * Fired on the main thread when a tester clicks an entry a game mode added through [TestToolEvent]. The plugin that
 * owns [id] performs the action and sets [handled]; the use is logged either way.
 *
 * @property player the tester.
 * @property id the clicked entry id.
 * @property handled whether a listener performed the action.
 */
class TestToolUseEvent(val player: Player, val id: String, var handled: Boolean = false) : Event() {
    /** Bukkit handler list. */
    override fun getHandlers(): HandlerList = HANDLERS

    /** Bukkit handler list. */
    companion object {
        private val HANDLERS = HandlerList()

        /** @return the handler list. */
        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
