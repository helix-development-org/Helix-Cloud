package org.helix.addons.bettermsgs.paper

/**
 * Pure helpers of the letters GUI: scroll clamping, paging and message
 * text wrapping.
 */
object ChatMath {
    /** Letters visible at once — one per ledger row. */
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
     * Maps a scroll offset onto ledger pages: the page arrows step one window
     * back (left, older) or forward (right, newer).
     *
     * @param offset current scroll offset.
     * @param total total message count.
     * @return current page (0 = oldest) to page count.
     */
    fun pages(offset: Int, total: Int): Pair<Int, Int> {
        val clamped = clampOffset(offset, total)
        val older = (total - clamped - WINDOW).coerceAtLeast(0).let { (it + WINDOW - 1) / WINDOW }
        val newer = (clamped + WINDOW - 1) / WINDOW
        return older to older + 1 + newer
    }

    /**
     * Shortens text to a maximum length, appending `..` when cut.
     *
     * @param text raw message text.
     * @param max maximum characters.
     * @return the possibly shortened text.
     */
    fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take((max - 2).coerceAtLeast(1)).trimEnd() + ".."

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
