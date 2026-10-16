package org.helix.addons.lobby.paper

/**
 * Fallbacks for navigator modes without configuration, guessed from the task name (pure, unit-tested): place on the
 * map, emblem and whether a click opens the instance picker.
 */
object NavigatorGuess {
    /** Places filled in this order when a mode names none and its task gives no hint. */
    val AUTO_ORDER = listOf("kesseldorf", "rosenarena", "grenzlande", "reserve", "kronstadt", "ankerplatz", "nebelland")

    /** Place for a task without configuration, guessed from its name. */
    fun place(task: String): String {
        val t = task.lowercase()
        return when {
            "lobby" in t || "hub" in t -> "ankerplatz"
            "village" in t || "dorf" in t || "clash" in t -> "kesseldorf"
            "1v1" in t || "duel" in t || "arena" in t -> "rosenarena"
            "war" in t || "krieg" in t -> "grenzlande"
            "city" in t || "stadt" in t -> "kronstadt"
            else -> ""
        }
    }

    /** Emblem for a task without configuration, guessed from its name. */
    fun emblem(task: String): String {
        val t = task.lowercase()
        return when {
            "lobby" in t || "hub" in t -> "castle"
            "city" in t || "stadt" in t -> "crown"
            "war" in t || "krieg" in t -> "banner"
            "clash" in t || "pvp" in t || "fight" in t || "duel" in t -> "swords"
            "event" in t || "vip" in t || "premium" in t -> "crown"
            else -> "compass"
        }
    }

    /** Whether a task without configuration opens the instance picker (CityBuild: choose your city). */
    fun pickInstance(task: String): Boolean {
        val t = task.lowercase()
        return "city" in t || "stadt" in t
    }
}
