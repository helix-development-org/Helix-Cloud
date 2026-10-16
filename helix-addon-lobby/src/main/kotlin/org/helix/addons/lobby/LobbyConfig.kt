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
                    name = "<#ffcf4a>Server",
                    lore = listOf("<#ffcf4a>Click: switch server"),
                    action = ItemAction.OPEN_SERVER_MENU,
                    glow = true,
                ),
                LobbyItem(
                    slot = 4,
                    material = "PLAYER_HEAD",
                    name = "<#ffcf4a>Profile",
                    lore = listOf("<#ffcf4a>Click: open your profile"),
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
 * The navigator (server selector): a full-screen medieval board with one coat of arms per game mode.
 *
 * @property title headline on the board's ribbon (plain text).
 * @property excludeLobbyTasks whether lobby tasks are left out.
 * @property modes how game modes appear (name, emblem, order); tasks without an entry get their task name and an
 *   emblem guessed from the name.
 */
@Serializable
data class ServerMenuSettings(
    val title: String = "Karte des Reichs",
    val excludeLobbyTasks: Boolean = true,
    val modes: List<NavigatorMode> = emptyList(),
)

/**
 * One game mode of the navigator.
 *
 * @property task task name the mode connects to.
 * @property name display name on the coat of arms (empty: the task name).
 * @property emblem emblem id (`castle`, `swords`, `banner`, `compass`, `crown`).
 * @property place place on the map (`ankerplatz`, `kesseldorf`, `rosenarena`, `grenzlande`, `nebelland`, `reserve`,
 *   `kronstadt`; empty: guessed from the task name).
 * @property villager villager id whose head stands beside the place (empty: the place's default).
 * @property lore one sentence of lore in the tooltip (empty: the place's default text).
 * @property maintenance whether the place is shown under maintenance (grey, not clickable).
 * @property pickInstance whether a click opens the instance picker (a ledger listing every backend of the task, e.g.
 *   `CityBuild-1..3`) instead of connecting to the least-loaded backend. Configs stored before this field existed
 *   decode with `false`.
 */
@Serializable
data class NavigatorMode(
    val task: String,
    val name: String = "",
    val emblem: String = "compass",
    val place: String = "",
    val villager: String = "",
    val lore: String = "",
    val maintenance: Boolean = false,
    val pickInstance: Boolean = false,
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
