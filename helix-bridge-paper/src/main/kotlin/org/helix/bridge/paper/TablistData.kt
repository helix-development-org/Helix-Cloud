package org.helix.bridge.paper

import kotlinx.serialization.Serializable

/**
 * The tab list configuration published by the tablist addon as the
 * `tablist.config` bridge value, including animation frames.
 *
 * @property header header text of the first frame.
 * @property footer footer text of the first frame.
 * @property headerFrames all header frames; empty means `[header]`.
 * @property footerFrames all footer frames; empty means `[footer]`.
 * @property intervalMs milliseconds between animation frames.
 */
@Serializable
data class TablistData(
    val header: String = "",
    val footer: String = "",
    val headerFrames: List<String> = emptyList(),
    val footerFrames: List<String> = emptyList(),
    val intervalMs: Long = 1000,
) {
    /**
     * Number of animation frames (the longer of both frame lists).
     *
     * @return at least `1`.
     */
    fun frameCount(): Int = maxOf(headerFrames.size, footerFrames.size, 1)

    /**
     * The frame index active at the given time.
     *
     * @param nowEpochMs current epoch millis.
     * @return index in `[0, frameCount)`.
     */
    fun frameIndexAt(nowEpochMs: Long): Int =
        ((nowEpochMs / intervalMs.coerceAtLeast(1)) % frameCount()).toInt()

    /**
     * Header text of a frame (frame lists fall back to the base header).
     *
     * @param index frame index.
     * @return the header text with `\n` line breaks unescaped.
     */
    fun headerAt(index: Int): String =
        (if (headerFrames.isEmpty()) header else headerFrames[index % headerFrames.size])
            .replace("\\n", "\n")

    /**
     * Footer text of a frame (frame lists fall back to the base footer).
     *
     * @param index frame index.
     * @return the footer text with `\n` line breaks unescaped.
     */
    fun footerAt(index: Int): String =
        (if (footerFrames.isEmpty()) footer else footerFrames[index % footerFrames.size])
            .replace("\\n", "\n")
}
