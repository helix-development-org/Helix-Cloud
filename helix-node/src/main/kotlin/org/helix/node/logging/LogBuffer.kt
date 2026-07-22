package org.helix.node.logging

/**
 * In-memory ring buffer of the node's most recent log lines.
 *
 * The buffer is filled by teeing `System.out`/`System.err` so it captures
 * everything the node and its libraries print, and exposed to the
 * dashboard through the control API.
 *
 * @property capacity maximum number of retained lines.
 */
class LogBuffer(private val capacity: Int = 2000) {
    private val lines = ArrayDeque<String>()
    private var total: Long = 0

    /**
     * Appends a line, dropping the oldest when the capacity is reached.
     *
     * @param line the log line without trailing newline.
     */
    @Synchronized
    fun add(line: String) {
        lines.addLast(line)
        total++
        while (lines.size > capacity) {
            lines.removeFirst()
        }
    }

    /**
     * Returns the newest lines.
     *
     * @param limit maximum number of lines from the end.
     * @return the lines, oldest first.
     */
    @Synchronized
    fun tail(limit: Int): List<String> = lines.toList().takeLast(limit)

    /**
     * Total lines ever appended — the streaming offset.
     *
     * @return the monotonically increasing line count.
     */
    @Synchronized
    fun offset(): Long = total

    /**
     * Lines appended after a previous [offset], for log streaming.
     *
     * @param offset the last seen offset.
     * @return the new lines (bounded by the buffer capacity), oldest first.
     */
    @Synchronized
    fun since(offset: Long): List<String> {
        val missed = (total - offset).coerceIn(0, lines.size.toLong()).toInt()
        return lines.toList().takeLast(missed)
    }
}
