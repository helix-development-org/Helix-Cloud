package org.helix.api.release

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Answer of one addon to the network release reset (`<addon>.release.reset plan|run`).
 *
 * Every addon that owns player progress registers such an action; the node's `release.reset` orchestrator
 * discovers them by the [ReleaseReset.ACTION_SUFFIX] and calls each one with [ReleaseReset.PLAN] (dry run) or
 * [ReleaseReset.RUN].
 *
 * @property addon addon id that answered.
 * @property mode [ReleaseReset.PLAN] or [ReleaseReset.RUN].
 * @property deleted data sets that are (plan) or were (run) deleted, with their record counts.
 * @property kept data sets that are deliberately kept, with a short reason.
 * @property notes free-form remarks shown to the operator (for example granted rewards).
 */
@Serializable
data class ReleaseReport(
    val addon: String,
    val mode: String,
    val deleted: Map<String, Int> = emptyMap(),
    val kept: Map<String, String> = emptyMap(),
    val notes: List<String> = emptyList(),
) {
    /** @return this report as one JSON line for an action result. */
    fun toJson(): String = ReleaseReset.json.encodeToString(serializer(), this)
}

/** Shared names of the release reset protocol. */
object ReleaseReset {
    /** Suffix of every participating addon action, for example `eco.release.reset`. */
    const val ACTION_SUFFIX: String = ".release.reset"

    /** Permission guarding the orchestrator and every participant action. */
    const val PERMISSION: String = "release.reset"

    /** Dry run: count what would be deleted, change nothing. */
    const val PLAN: String = "plan"

    /** Perform the reset. */
    const val RUN: String = "run"

    /** Optional flag of [RUN]: also remove all members from the `tester` group. */
    const val FLAG_REMOVE_TESTERS: String = "--remove-testers"

    /** Shared JSON codec of the protocol. */
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Parses the mode argument of a participant action.
     *
     * @param arguments action arguments.
     * @return `true` for a dry run ([PLAN]), `false` for [RUN], `null` for anything else.
     */
    fun dryRun(arguments: List<String>): Boolean? = when (arguments.firstOrNull()?.lowercase()) {
        PLAN -> true
        RUN -> false
        else -> null
    }

    /**
     * Decodes a participant answer.
     *
     * @param line first result line of a participant action.
     * @return the report, or `null` when the line is not a report.
     */
    fun parse(line: String?): ReleaseReport? =
        line?.let { runCatching { json.decodeFromString(ReleaseReport.serializer(), it) }.getOrNull() }
}
