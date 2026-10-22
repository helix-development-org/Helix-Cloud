package org.helix.addons.feedback.paper

/**
 * Severity of a bug report, with the wire id of the node contract (`helix-addon-feedback/ACTIONS.md`).
 *
 * @property wire the id sent to and received from the node.
 * @property staffOnly reports of this severity are visible to staff and their reporter only.
 */
enum class Severity(val wire: String, val staffOnly: Boolean = false) {
    /** Kleinigkeit. */
    MINOR("minor"),

    /** Störend. */
    ANNOYING("annoying"),

    /** Blockierend. */
    BLOCKING("blocking"),

    /** Absturz. */
    CRASH("crash"),

    /** Exploit – only the team sees it. */
    EXPLOIT("exploit", staffOnly = true),
    ;

    /** Translation key of the display name. */
    val langKey: String get() = "severity.$wire"

    /** Lookup by wire id. */
    companion object {
        /**
         * @param wire a wire id.
         * @return the severity, or null for unknown ids.
         */
        fun of(wire: String?): Severity? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Area a bug report belongs to.
 *
 * @property wire the id of the node contract.
 */
enum class Category(val wire: String) {
    /** MineClash. */
    MINECLASH("mineclash"),

    /** CityBuild. */
    CITYBUILD("citybuild"),

    /** Lobby. */
    LOBBY("lobby"),

    /** Network-wide (proxy, chat, …). */
    NETWORK("network"),

    /** Graphics and texts. */
    GRAPHICS("graphics"),

    /** Anything else. */
    OTHER("other"),
    ;

    /** Translation key of the display name. */
    val langKey: String get() = "category.$wire"

    /** Lookup and defaults. */
    companion object {
        /**
         * @param wire a wire id.
         * @return the category, or null for unknown ids.
         */
        fun of(wire: String?): Category? = entries.firstOrNull { it.wire == wire }

        /**
         * Guesses the category from the Helix task the report is sent from.
         *
         * @param task the `HELIX_TASK` of this server.
         * @return the matching category, [OTHER] if the task is unknown.
         */
        fun forTask(task: String): Category {
            val t = task.lowercase()
            return when {
                "mineclash" in t -> MINECLASH
                "citybuild" in t || "city" in t -> CITYBUILD
                "lobby" in t -> LOBBY
                else -> OTHER
            }
        }
    }
}

/**
 * Status of a report as the node reports it.
 *
 * @property wire the id of the node contract.
 * @property open whether the report still awaits work.
 */
enum class ReportStatus(val wire: String, val open: Boolean) {
    /** Neu. */
    NEW("new", true),

    /** Bestätigt. */
    CONFIRMED("confirmed", true),

    /** In Arbeit. */
    IN_PROGRESS("in_progress", true),

    /** Behoben, wartet auf Deploy. */
    FIXED("fixed", true),

    /** Geschlossen. */
    CLOSED("closed", false),

    /** Abgelehnt. */
    REJECTED("rejected", false),

    /** Duplikat. */
    DUPLICATE("duplicate", false),

    /** Kein Bug. */
    NOT_A_BUG("not_a_bug", false),
    ;

    /** Translation key of the display name. */
    val langKey: String get() = "status.$wire"

    /** Lookup by wire id. */
    companion object {
        /**
         * @param wire a wire id.
         * @return the status, [NEW] for unknown ids.
         */
        fun of(wire: String?): ReportStatus = entries.firstOrNull { it.wire == wire } ?: NEW
    }
}

/**
 * The bug form a player is filling in.
 *
 * @property category chosen area.
 * @property severity chosen severity, null until picked.
 * @property title short title.
 * @property description free description.
 * @property screenshot whether a screenshot should be attached (LabyMod only).
 * @property confirming the send button was clicked once and waits for the confirming second click.
 */
data class ReportDraft(
    val category: Category = Category.OTHER,
    val severity: Severity? = null,
    val title: String = "",
    val description: String = "",
    val screenshot: Boolean = false,
    val confirming: Boolean = false,
)

/** Validation rules of the bug form (pure, unit-tested). */
object FormValidation {
    /** Minimum title length. */
    const val TITLE_MIN = 4

    /** Maximum title length (the ledger row shows about this much). */
    const val TITLE_MAX = 60

    /** Minimum description length. */
    const val DESCRIPTION_MIN = 10

    /** Maximum description length. */
    const val DESCRIPTION_MAX = 2000

    /**
     * Normalizes typed text: trims, collapses whitespace and strips legacy colour codes and MiniMessage tags so
     * nobody injects formatting into staff tools.
     *
     * @param raw the typed text.
     * @return the cleaned text.
     */
    fun clean(raw: String): String = raw
        .replace(Regex("§[0-9a-fk-orx]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<[^>]{1,40}>"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Checks a draft before it is sent.
     *
     * @param draft the draft.
     * @return the translation keys of all problems; empty when the draft can be sent.
     */
    fun problems(draft: ReportDraft): List<String> = buildList {
        if (draft.severity == null) add("form.error.severity")
        if (draft.title.length < TITLE_MIN) add("form.error.title-short")
        if (draft.title.length > TITLE_MAX) add("form.error.title-long")
        if (draft.description.length < DESCRIPTION_MIN) add("form.error.description-short")
        if (draft.description.length > DESCRIPTION_MAX) add("form.error.description-long")
    }

    /**
     * Cycles an enum value to the next entry (wrapping), used by the form rows.
     *
     * @param values all values in display order.
     * @param current the current value, null starts at the first.
     * @return the next value.
     */
    fun <T> next(values: List<T>, current: T?): T {
        val index = values.indexOf(current)
        return values[(index + 1) % values.size]
    }
}
