package org.helix.addons.admin.paper

/**
 * In-game operations of the admin panel and the permission each one needs (pure, testable).
 *
 * Ops are sent by the client mod as `call {op, args}`; network-level actions (kick, warn, ban, message,
 * announce, server restarts) do not come through here – the panel page calls them on the node directly.
 */
object StaffOps {
    /** Permission to open the panel at all. */
    const val MENU = "helix.staff.menu"

    private val permissions = mapOf(
        "self.noclip" to "helix.staff.self",
        "self.heal" to "helix.staff.self",
        "self.feed" to "helix.staff.self",
        "self.fly" to "helix.staff.self",
        "self.vanish" to "helix.staff.self",
        "self.gamemode" to "helix.staff.self",
        "self.tp" to "helix.staff.self",
        "self.spawn" to "helix.staff.self",
        "world.time" to "helix.staff.world",
        "world.weather" to "helix.staff.world",
        "world.clear" to "helix.staff.world",
        "server.info" to MENU,
        "player.goto" to "helix.staff.teleport",
        "player.bring" to "helix.staff.teleport",
        "player.spectate" to "helix.staff.spectate",
        "player.freeze" to "helix.staff.freeze",
        "player.heal" to "helix.staff.heal",
        "player.feed" to "helix.staff.heal",
        "player.gamemode" to "helix.staff.gamemode",
        "player.invsee" to "helix.staff.inventory",
    )

    /** All known ops. */
    val ops: Set<String> get() = permissions.keys

    /**
     * Permission node for [op].
     *
     * @param op operation name.
     * @return the node, or `null` for unknown ops (rejected).
     */
    fun permissionFor(op: String): String? = permissions[op]

    /** Game ticks for the `world.time` presets. */
    val TIME_PRESETS: Map<String, Long> = mapOf("day" to 1_000L, "noon" to 6_000L, "night" to 13_000L, "midnight" to 18_000L)

    /** Largest radius for `world.clear`. */
    const val MAX_CLEAR_RADIUS = 64
}
