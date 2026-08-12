package org.helix.addons.phone.paper

import kotlinx.serialization.Serializable

/**
 * Paper-side mirror of the phone node addon's render model. The two sides
 * agree on the JSON shape of `phone.apps` and `phone.servers`.
 */
@Serializable
enum class AppKind {
    /** Runs [AppView.command] as the tapping player. */
    COMMAND,

    /** Opens the native phone screen [AppView.screen]. */
    NATIVE,
}

/**
 * One app as resolved for a player, including its icon glyph.
 *
 * @property id the app id.
 * @property name the display label (MiniMessage).
 * @property kind command vs native.
 * @property command the command for a command app.
 * @property screen the native screen id for a native app.
 * @property order sort order.
 * @property iconFont the icon glyph's base font (e.g. `helix_phone:icons`);
 *   the row suffix (`_row<n>`) is appended per home-screen row.
 * @property iconChar the icon glyph as a one-character string.
 */
@Serializable
data class AppView(
    val id: String,
    val name: String = "",
    val kind: AppKind = AppKind.COMMAND,
    val command: String = "",
    val screen: String = "",
    val order: Int = 0,
    val iconFont: String = "",
    val iconChar: String = "",
)

/**
 * A joinable backend for the navigator app.
 *
 * @property id the service id (`<task>-<index>`) to connect to.
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
