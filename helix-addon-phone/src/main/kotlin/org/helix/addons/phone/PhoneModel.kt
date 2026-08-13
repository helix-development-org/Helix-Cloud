package org.helix.addons.phone

import kotlinx.serialization.Serializable

/**
 * The phone configuration: the ordered set of apps shown on the home
 * screen. Persisted under the `phone` storage key and edited from the
 * dashboard panel. The per-app [PhoneApp.sinceEpoch] is managed by the node,
 * not the panel.
 *
 * @property apps the configured apps in display order.
 */
@Serializable
data class PhoneConfig(
    val apps: List<PhoneApp> = DEFAULT_APPS,
) {
    companion object {
        /**
         * The apps shipped out of the box. Admin apps are marked
         * [PhoneApp.adminOnly] so normal players never see them.
         */
        val DEFAULT_APPS: List<PhoneApp> = listOf(
            PhoneApp("messages", "<white>Messages", AppKind.NATIVE, screen = "messages", icon = "builtin:messages", order = 0),
            PhoneApp("navigator", "<white>Navigator", AppKind.NATIVE, screen = "navigator", icon = "builtin:navigator", order = 1),
            PhoneApp("profile", "<white>Profile", AppKind.COMMAND, command = "profilemenu", icon = "builtin:profile", order = 2),
            PhoneApp("settings", "<white>Settings", AppKind.NATIVE, screen = "settings", icon = "builtin:settings", order = 3),
            PhoneApp(
                "guard", "<red>iGuard", AppKind.COMMAND, command = "iguard panel",
                permission = "iguard.panel", adminOnly = true, icon = "builtin:guard", order = 4,
            ),
            PhoneApp(
                "network", "<red>Network", AppKind.NATIVE, screen = "network",
                permission = "helix.phone.admin", adminOnly = true, icon = "builtin:network", order = 5,
            ),
        )
    }
}

/**
 * A single home-screen app.
 *
 * @property id stable slug, unique per phone; used for ordering and native
 *   screen routing.
 * @property name the display label as MiniMessage.
 * @property kind whether a tap runs a command or opens a native phone screen.
 * @property command the command to run (no leading `/`) when [kind] is
 *   [AppKind.COMMAND]; the tapping player runs it.
 * @property screen the native screen id when [kind] is [AppKind.NATIVE], for
 *   example `messages`, `settings`, `navigator`, `network`.
 * @property permission optional permission node required to see the app.
 * @property adminOnly whether the app requires `helix.phone.admin`.
 * @property icon icon reference: `builtin:<name>` for a baked icon or
 *   `custom:<iconId>` for an uploaded one.
 * @property order sort order on the home screen.
 * @property enabled whether the app is shown at all.
 * @property sinceEpoch the phone epoch at which this app was added; a player
 *   only sees it once they joined at or after this epoch (node-managed).
 */
@Serializable
data class PhoneApp(
    val id: String,
    val name: String = "",
    val kind: AppKind = AppKind.COMMAND,
    val command: String = "",
    val screen: String = "",
    val permission: String = "",
    val adminOnly: Boolean = false,
    val icon: String = "builtin:default",
    val order: Int = 0,
    val enabled: Boolean = true,
    val sinceEpoch: Int = 0,
)

/**
 * What tapping an app does.
 */
@Serializable
enum class AppKind {
    /** Runs [PhoneApp.command] as the tapping player. */
    COMMAND,

    /** Opens the native phone screen [PhoneApp.screen]. */
    NATIVE,
}

/**
 * The render-ready view of an app for one player, returned by the
 * `phone.apps` action. Carries the resolved icon glyph so the paper side
 * draws it without knowing the icon registry.
 *
 * @property id the app id.
 * @property name the display label (MiniMessage).
 * @property kind command vs native.
 * @property command the command for a command app.
 * @property screen the native screen id for a native app.
 * @property order sort order.
 * @property iconFont the icon glyph's base font, for example
 *   `helix_phone:icons` or `helix_phone:uploads`; the paper side appends the
 *   grid row (`_row<n>`).
 * @property iconChar the icon glyph codepoint as a one-character string.
 */
@Serializable
data class AppView(
    val id: String,
    val name: String,
    val kind: AppKind,
    val command: String,
    val screen: String,
    val order: Int,
    val iconFont: String,
    val iconChar: String,
)

/**
 * A joinable backend for the navigator app, returned by `phone.servers`.
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
