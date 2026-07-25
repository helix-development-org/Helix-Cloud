package org.helix.addons.bettermsgs.paper

/**
 * Pure helpers of the chat GUI: scroll clamping, scrollbar thumb position
 * and message text wrapping.
 */
object ChatMath {
    /** Messages visible at once (5 chest rows + 3 player-inventory rows). */
    const val WINDOW = 8

    /**
     * Clamps a scroll offset (messages back from the newest).
     *
     * @param offset requested offset.
     * @param total total message count.
     * @return offset within `0..max(0, total - WINDOW)`.
     */
    fun clampOffset(offset: Int, total: Int): Int =
        offset.coerceIn(0, (total - WINDOW).coerceAtLeast(0))

    /**
     * Picks the scrollbar thumb variant for an offset.
     *
     * Thumb 7 is the bottom (newest messages, offset 0), thumb 0 the top
     * (oldest window).
     *
     * @param offset current scroll offset.
     * @param total total message count.
     * @return thumb index in `0..7`.
     */
    fun thumbIndex(offset: Int, total: Int): Int {
        val range = total - WINDOW
        if (range <= 0) {
            return 7
        }
        return Math.round((1.0 - clampOffset(offset, total).toDouble() / range) * 7).toInt().coerceIn(0, 7)
    }

    /**
     * Wraps message text into display lines.
     *
     * @param text raw message text.
     * @param width maximum characters per line.
     * @return at least one line, words kept intact where possible.
     */
    fun wrap(text: String, width: Int = 38): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            return listOf("")
        }
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        words.forEach { word ->
            val chunks = if (word.length > width) word.chunked(width) else listOf(word)
            chunks.forEach { chunk ->
                if (current.isNotEmpty() && current.length + 1 + chunk.length > width) {
                    lines += current.toString()
                    current.setLength(0)
                }
                if (current.isNotEmpty()) {
                    current.append(' ')
                }
                current.append(chunk)
            }
        }
        if (current.isNotEmpty()) {
            lines += current.toString()
        }
        return lines
    }
}
