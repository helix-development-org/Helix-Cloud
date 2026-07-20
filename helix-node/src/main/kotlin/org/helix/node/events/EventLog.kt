package org.helix.node.events

/**
 * In-memory ring buffer of recent node events.
 *
 * Fed from service lifecycle, player join/leave, the notification bus and
 * mutating actions; read by the dashboard.
 *
 * @property capacity maximum number of retained events.
 * @property clock epoch millis source, injectable for tests.
 */
class EventLog(
    private val capacity: Int = 500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val events = ArrayDeque<Event>()

    /**
     * Records an event.
     *
     * @param category coarse grouping.
     * @param message human readable description.
     * @param level severity, defaults to `info`.
     */
    @Synchronized
    fun record(category: String, message: String, level: String = "info") {
        events.addLast(Event(clock(), category, level, message))
        while (events.size > capacity) {
            events.removeFirst()
        }
    }

    /**
     * Returns the newest events first.
     *
     * @param limit maximum number of events.
     * @return events, newest first.
     */
    @Synchronized
    fun recent(limit: Int): List<Event> = events.toList().takeLast(limit).asReversed()
}
