package org.helix.addons.maprotation

import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionResult

/**
 * Node-coordinated map/world rotation orchestration.
 *
 * An operator configures a named, ordered list of maps
 * (`maprotation.configure <id> <map1,map2,...>`) and wires
 * `maprotation.advance <id>` into either a scheduled job (`POST /jobs`,
 * `everyMinutes`) for a timer-driven rotation, or has their own game logic
 * invoke it directly on a round-end signal. `maprotation.current`/
 * `maprotation.next` expose the state for the dashboard or any bridge to
 * poll. This addon only decides and broadcasts which map is next — it
 * never loads worlds or teleports players; that mechanic belongs to a
 * Paper-side bridge component of this addon (or a server's own plugin)
 * that reacts to the `maprotation` notification / calls back into these
 * actions.
 */
class MapRotationAddon : AddonBase() {
    private lateinit var store: RotationStore

    /**
     * Registers the `maprotation.*` orchestration actions.
     */
    override fun enable() {
        store = RotationStore(context.storage())

        action(
            "maprotation.configure",
            "Sets (or replaces) a rotation's ordered map list.",
            "maprotation.configure <id> <map1,map2,...>",
        ) { inv ->
            val id = inv.arguments.getOrNull(0)
            val maps = inv.arguments.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (id == null || maps.isNullOrEmpty()) {
                return@action ActionResult.error("usage: maprotation.configure <id> <map1,map2,...>")
            }
            val state = store.configure(id, maps)
            publishState(id)
            ActionResult.ok("$id now cycles ${state.maps.size} maps, current: ${state.maps[state.currentIndex]}")
        }
        action("maprotation.remove", "Removes a rotation entirely.", "maprotation.remove <id>") { inv ->
            val id = inv.arguments.getOrNull(0)
                ?: return@action ActionResult.error("usage: maprotation.remove <id>")
            if (!store.remove(id)) {
                ActionResult.error("unknown rotation: $id")
            } else {
                ActionResult.ok("removed rotation $id")
            }
        }
        action("maprotation.current", "Shows a rotation's current map.", "maprotation.current <id>") { inv ->
            val id = inv.arguments.getOrNull(0)
                ?: return@action ActionResult.error("usage: maprotation.current <id>")
            store.current(id)?.let { ActionResult.ok(it) } ?: ActionResult.error("unknown rotation: $id")
        }
        action(
            "maprotation.next",
            "Peeks at the map a rotation would advance to, without changing it.",
            "maprotation.next <id>",
        ) { inv ->
            val id = inv.arguments.getOrNull(0)
                ?: return@action ActionResult.error("usage: maprotation.next <id>")
            store.peekNext(id)?.let { ActionResult.ok(it) } ?: ActionResult.error("unknown rotation: $id")
        }
        action(
            "maprotation.advance",
            "Advances a rotation to its next map and broadcasts the change.",
            "maprotation.advance <id>",
        ) { inv ->
            val id = inv.arguments.getOrNull(0)
                ?: return@action ActionResult.error("usage: maprotation.advance <id>")
            val previous = store.current(id)
            val next = store.advance(id) ?: return@action ActionResult.error("unknown rotation: $id")
            publishState(id)
            context.publishNotification("maprotation", "$id: $previous -> $next")
            ActionResult.ok(next)
        }
        action("maprotation.list", "Lists configured rotations with their current map.", "maprotation.list") {
            ActionResult.ok(*store.rotationIds().map { id -> "$id: ${store.current(id)}" }.toTypedArray())
        }
    }

    /** Publishes a rotation's current/next map as bridge values for pollers. */
    private fun publishState(id: String) {
        val key = id.lowercase()
        context.publishBridgeValue("maprotation.$key.current", store.current(id) ?: "")
        context.publishBridgeValue("maprotation.$key.next", store.peekNext(id) ?: "")
    }
}
