package org.helix.addons.lobby.paper

import kotlinx.serialization.Serializable

/**
 * Paper-side mirror of the `helix.lobby` node addon's configuration model.
 *
 * The two sides never share a jar — they agree on the JSON shape of the
 * `lobby.config` bridge value and the `lobby.servers` action, exactly as the
 * other split addons (npc, …) do. Keep the field names in sync with
 * `org.helix.addons.lobby.LobbyConfig`.
 */
@Serializable
data class LobbyConfig(
    val lobbyTasks: List<String> = emptyList(),
    val layouts: Map<String, LobbyLayout> = emptyMap(),
    val protection: ProtectionSettings = ProtectionSettings(),
    val serverMenu: ServerMenuSettings = ServerMenuSettings(),
    val phone: PhoneItemSettings = PhoneItemSettings(),
) {
    /** The layout a lobby task should use: its own, else the `*` fallback, else empty. */
    fun layoutFor(task: String): LobbyLayout = layouts[task] ?: layouts["*"] ?: LobbyLayout()

    /** Whether a task's backends should behave as lobbies. */
    fun isLobbyTask(task: String): Boolean = task in lobbyTasks
}

/** One task's hotbar layout. */
@Serializable
data class LobbyLayout(
    val items: List<LobbyItem> = emptyList(),
)

/** A single lobby hotbar item that runs an action when clicked. */
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

/** What clicking a lobby item does. */
@Serializable
enum class ItemAction { RUN_COMMAND, OPEN_SERVER_MENU }

/** The toggleable lobby protection rules. */
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

/** Phone-mode: a single hotbar item that opens `/phone` replaces the layout. */
@Serializable
data class PhoneItemSettings(
    val enabled: Boolean = false,
    val slot: Int = 4,
    val material: String = "COMPASS",
    val name: String = "<green>Phone",
)

/** The built-in server selector's appearance. */
@Serializable
data class ServerMenuSettings(
    val title: String = "<dark_gray>Server selector",
    val rows: Int = 3,
    val excludeLobbyTasks: Boolean = true,
    val entryMaterial: String = "PAPER",
)

/** A joinable backend returned by the `lobby.servers` action. */
@Serializable
data class ServerEntry(
    val id: String,
    val task: String,
    val players: Int = 0,
    val maxPlayers: Int = 0,
)
