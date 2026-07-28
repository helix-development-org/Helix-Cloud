package org.helix.addons.subtitles.paper

/**
 * Pure parsing of the subtitle-specific slice of a bridge-values fetch.
 */
object SubtitleValues {
    private const val PREFIX = "subtitle.text."

    /**
     * Extracts `subtitle.text.<player>` entries, keyed back to the plain
     * lowercase player name.
     *
     * @param bridgeValues the full bridge-values map.
     * @return lowercase player name to subtitle text.
     */
    fun parse(bridgeValues: Map<String, String>): Map<String, String> =
        bridgeValues.mapNotNull { (key, value) ->
            key.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.let { it to value }
        }.toMap()
}
