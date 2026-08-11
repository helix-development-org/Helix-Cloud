package org.helix.addons.lobby

import kotlinx.serialization.Serializable

/**
 * The whole lobby configuration, persisted under the `lobby` storage key and
 * republished as the `lobby.config` bridge value on every change so every
 * lobby backend picks it up.
 *
 * A backend counts as a lobby when its `HELIX_TASK` is listed in
 * [lobbyTasks]; only then does the paper component hand out items, enforce
 * protection and take over the hotbar. Every other task ignores the addon
 * even while it is installed.
 *
 * @property lobbyTasks task names whose services act as lobbies.
 * @property layouts hotbar layout per task name; the `*` entry is the
 *   fallback used by any lobby task without its own layout.
 * @property protection the lobby protection rules, each toggleable.
 * @property serverMenu the built-in server selector's appearance.
 */
@Serializable
data class LobbyConfig(
    val lobbyTasks: List<String> = emptyList(),
    val layouts: Map<String, LobbyLayout> = mapOf("*" to LobbyLayout.DEFAULT),
    val protection: ProtectionSettings = ProtectionSettings(),
    val serverMenu: ServerMenuSettings = ServerMenuSettings(),
) {
    /**
     * Resolves the layout a lobby task should use: its own if present,
     * otherwise the `*` fallback, otherwise an empty layout.
     *
     * @param task the backend's task name.
     * @return the layout to apply.
     */
    fun layoutFor(task: String): LobbyLayout =
        layouts[task] ?: layouts["*"] ?: LobbyLayout()

    /**
     * Whether a task's backends should behave as lobbies.
     *
     * @param task the backend's task name.
     * @return `true` when the task is configured as a lobby.
     */
    fun isLobbyTask(task: String): Boolean = task in lobbyTasks
}

/**
 * One task's hotbar layout: the items a joining player receives, keyed by
 * their target slot.
 *
 * @property items the configured hotbar items.
 */
@Serializable
data class LobbyLayout(
    val items: List<LobbyItem> = emptyList(),
) {
    companion object {
        /** A sensible starter layout: profile, translations and a server compass. */
        val DEFAULT = LobbyLayout(
            items = listOf(
                LobbyItem(
                    slot = 0,
                    material = "COMPASS",
                    name = "<green>Server",
                    lore = listOf("<gray>Click to switch server"),
                    action = ItemAction.OPEN_SERVER_MENU,
                    glow = true,
                ),
                LobbyItem(
                    slot = 4,
                    material = "PLAYER_HEAD",
                    name = "<aqua>Profile",
                    lore = listOf("<gray>Open your profile menu"),
                    action = ItemAction.RUN_COMMAND,
                    command = "profilemenu",
                ),
            ),
        )
    }
}

/**
 * A single lobby hotbar item that runs an action when clicked.
 *
 * @property slot hotbar slot 0..8 the item occupies.
 * @property material the Bukkit material name (invalid names fall back to a
 *   barrier on the paper side, so a typo is visible in game).
 * @property name the display name as MiniMessage.
 * @property lore the lore lines as MiniMessage.
 * @property action what a click does.
 * @property command the command to run (without leading `/`) when [action]
 *   is [ItemAction.RUN_COMMAND]; the clicking player runs it themselves.
 * @property permission an optional permission node; players without it do
 *   not receive the item.
 * @property glow whether the item shows the enchantment glint.
 */
@Serializable
data class LobbyItem(
    val slot: Int = 0,
    val material: String = "COMPASS",
    val name: String = "",
    val lore: List<String> = emptyList(),
    val action: ItemAction = ItemAction.RUN_COMMAND,
    val command: String = "",
    val permission: String = "",
    val glow: Boolean = false,
)

/**
 * What clicking a lobby item does.
 */
@Serializable
enum class ItemAction {
    /** Runs [LobbyItem.command] as the clicking player. */
    RUN_COMMAND,

    /** Opens the built-in server selector GUI. */
    OPEN_SERVER_MENU,
}

/**
 * The lobby protection rules. Each flag is independently toggleable so an
 * operator can keep, for example, adventure mode without void teleport.
 *
 * @property adventureMode forces adventure game mode on join.
 * @property preventBlockBreak cancels block breaking.
 * @property preventBlockPlace cancels block placing.
 * @property preventItemDrop cancels dropping items.
 * @property preventItemMove cancels moving items within the inventory (keeps
 *   the hotbar layout intact).
 * @property preventDamage cancels all damage to lobby players.
 * @property preventHunger keeps the food bar full.
 * @property clearInventoryOnJoin empties the inventory before laying out the
 *   lobby items.
 * @property voidTeleport teleports a player back to spawn when they fall
 *   below [voidTeleportY].
 * @property voidTeleportY the Y level that triggers the void teleport.
 */
@Serializable
data class ProtectionSettings(
    val adventureMode: Boolean = true,
    val preventBlockBreak: Boolean = true,
    val preventBlockPlace: Boolean = true,
    val preventItemDrop: Boolean = true,
    val preventItemMove: Boolean = true,
    val preventDamage: Boolean = true,
    val preventHunger: Boolean = true,
    val clearInventoryOnJoin: Boolean = true,
    val voidTeleport: Boolean = true,
    val voidTeleportY: Int = 0,
)

/**
 * The built-in server selector GUI's appearance. Its content is dynamic —
 * the paper side asks the node for the live, joinable backends grouped by
 * task and connects the player through the proxy on click.
 *
 * @property title the GUI title as MiniMessage.
 * @property rows chest rows (1..6).
 * @property excludeLobbyTasks hides lobby tasks from the selector.
 * @property entryMaterial the material used for each server entry.
 */
@Serializable
data class ServerMenuSettings(
    val title: String = "<dark_gray>Server selector",
    val rows: Int = 3,
    val excludeLobbyTasks: Boolean = true,
    val entryMaterial: String = "PAPER",
)

/**
 * A joinable backend the server selector can connect to, as returned by the
 * `lobby.servers` action. One entry per running service.
 *
 * @property id the service id (`<task>-<index>`), the proxy-registered
 *   server name to connect to.
 * @property task the service's task name.
 * @property players current player count.
 * @property maxPlayers the service's player cap.
 */
@Serializable
data class ServerEntry(
    val id: String,
    val task: String,
    val players: Int = 0,
    val maxPlayers: Int = 0,
)
