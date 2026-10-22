package org.helix.addons.feedback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One in-game command of the tester guide (panel tab „Befehle“, resource `testguide/commands.json`).
 *
 * @property id stable id (`clan-create`).
 * @property mode game area, one of [TestGuide.MODES].
 * @property command the command as typed, with placeholders (`/clan create <Tag> <Name>`).
 * @property summary what the command does (German).
 * @property expected what a tester should see when trying it (German).
 * @property audience who may use it, one of [TestGuide.AUDIENCES].
 * @property permission required permission node, if any.
 */
@Serializable
data class GuideEntry(
    val id: String,
    val mode: String,
    val command: String,
    val summary: String,
    val expected: String,
    val audience: String,
    val permission: String? = null,
)

/**
 * The whole tester guide.
 *
 * @property version format version.
 * @property entries every documented command.
 */
@Serializable
data class GuideCatalog(val version: Int = 1, val entries: List<GuideEntry> = emptyList())

/**
 * Loads and checks the tester command guide shipped as `testguide/commands.json`.
 *
 * The catalog is maintained by hand from the command registrations of every module (node player commands, Paper
 * `plugin.yml`, MineClash CommandAPI, CityBuild); new in-game commands must be added there so testers can test them.
 */
object TestGuide {
    /** Resource path of the catalog inside the addon jar. */
    const val RESOURCE: String = "/testguide/commands.json"

    /** Allowed game areas, in display order. */
    val MODES: List<String> = listOf("Netzwerk", "Lobby", "MineClash", "CityBuild", "Testphase")

    /** Allowed audiences, in display order. */
    val AUDIENCES: List<String> = listOf("Spieler", "Tester", "Team")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads the bundled catalog.
     *
     * @return the raw JSON text, or `null` when the resource is missing.
     */
    fun raw(): String? = TestGuide::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Parses a catalog.
     *
     * @param text catalog JSON.
     * @return the catalog.
     */
    fun parse(text: String): GuideCatalog = json.decodeFromString(GuideCatalog.serializer(), text)

    /**
     * Checks a catalog: required fields, valid mode and audience, commands starting with `/` or a chat prefix `@`,
     * unique ids and unique commands.
     *
     * @param catalog the catalog.
     * @return problems, empty when the catalog is valid.
     */
    fun validate(catalog: GuideCatalog): List<String> {
        val problems = mutableListOf<String>()
        catalog.entries.forEach { e ->
            if (e.id.isBlank()) problems += "entry without id: ${e.command}"
            if (e.mode !in MODES) problems += "${e.id}: unknown mode ${e.mode}"
            if (e.audience !in AUDIENCES) problems += "${e.id}: unknown audience ${e.audience}"
            if (!(e.command.startsWith("/") || e.command.startsWith("@"))) problems += "${e.id}: command must start with / or @"
            if (e.summary.isBlank() || e.expected.isBlank()) problems += "${e.id}: summary and expected are required"
        }
        catalog.entries.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { problems += "duplicate id $it" }
        catalog.entries.groupBy { it.command }.filterValues { it.size > 1 }.keys.forEach { problems += "duplicate command $it" }
        return problems
    }
}
