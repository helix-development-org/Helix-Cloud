package org.helix.addons.tablist

import kotlinx.serialization.Serializable

/**
 * Persisted tab list configuration.
 *
 * When [headerFrames]/[footerFrames] contain more than one entry the tab
 * list is animated: the paper bridge cycles through the frames every
 * [intervalMs] milliseconds. [header]/[footer] remain the first frame for
 * backward compatibility.
 *
 * @property header header text of the first frame, `&` colors, `\n` breaks.
 * @property footer footer text of the first frame.
 * @property headerFrames all header frames; empty means `[header]`.
 * @property footerFrames all footer frames; empty means `[footer]`.
 * @property intervalMs milliseconds between animation frames.
 */
@Serializable
data class TablistConfig(
    val header: String = "&6Helix-Cloud",
    val footer: String = "&7{online}&8/&7{max} players",
    val headerFrames: List<String> = emptyList(),
    val footerFrames: List<String> = emptyList(),
    val intervalMs: Long = 1000,
) {
    /**
     * Effective header frames (falls back to the single header).
     *
     * @return at least one frame.
     */
    fun effectiveHeaderFrames(): List<String> = headerFrames.ifEmpty { listOf(header) }

    /**
     * Effective footer frames (falls back to the single footer).
     *
     * @return at least one frame.
     */
    fun effectiveFooterFrames(): List<String> = footerFrames.ifEmpty { listOf(footer) }
}
