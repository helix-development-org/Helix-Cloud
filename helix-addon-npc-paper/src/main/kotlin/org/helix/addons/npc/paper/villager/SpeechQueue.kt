package org.helix.addons.npc.paper.villager

/**
 * What a speaking villager does on a tick.
 */
sealed interface SpeechEvent {
    /**
     * A new line starts: show it in chat and play the "Hmm".
     *
     * @property text the line.
     */
    data class Line(val text: String) : SpeechEvent

    /** One babble syllable for a spoken letter. */
    data object Babble : SpeechEvent
}

/**
 * Tick-driven speech timeline of one villager (design concept §9/§16).
 *
 * Each line starts with the "Hmm" ([HMM_TICKS] long), then one babble per letter or digit every [ticksPerLetter]
 * ticks (spaces and punctuation stay silent but take time), then a short tail. At most two lines are spoken back to
 * back; the third waits [PAUSE_TICKS] (§9 "Max. 2 Zeilen, dann Pause").
 *
 * @property ticksPerLetter speaking tempo.
 */
class SpeechQueue(private val ticksPerLetter: Int = 2) {
    private val pending = ArrayDeque<String>()
    private var current: String? = null
    private var tick = 0
    private var cooldown = 0
    private var burst = 0
    private var idle = 0

    /** Whether a line is being spoken (mouth animation runs). */
    val speaking: Boolean get() = current != null

    /**
     * Queues a line.
     *
     * @param text the line (one chat line).
     * @return `false` when the queue is full and the line was dropped.
     */
    fun enqueue(text: String): Boolean {
        if (pending.size >= MAX_QUEUE) return false
        pending.addLast(text)
        return true
    }

    /** Drops everything queued and stops the current line. */
    fun clear() {
        pending.clear()
        current = null
    }

    /**
     * Advances one server tick.
     *
     * @return the events of this tick.
     */
    fun tick(): List<SpeechEvent> {
        val line = current
        if (line == null) {
            if (cooldown > 0) {
                cooldown--
                return emptyList()
            }
            val next = pending.removeFirstOrNull()
            if (next == null) {
                if (++idle >= PAUSE_TICKS) burst = 0
                return emptyList()
            }
            idle = 0
            current = next
            tick = 0
            burst++
            return listOf(SpeechEvent.Line(next))
        }
        tick++
        val step = tick - HMM_TICKS
        if (step >= 0 && step % ticksPerLetter == 0) {
            val index = step / ticksPerLetter
            if (index < line.length) {
                return if (line[index].isLetterOrDigit()) listOf(SpeechEvent.Babble) else emptyList()
            }
        }
        if (tick >= duration(line)) {
            current = null
            cooldown = if (burst >= 2) PAUSE_TICKS.also { burst = 0 } else GAP_TICKS
        }
        return emptyList()
    }

    /**
     * @param line a line.
     * @return how many ticks the line takes to speak.
     */
    fun duration(line: String): Int = HMM_TICKS + line.length * ticksPerLetter + TAIL_TICKS

    /** Timing constants. */
    companion object {
        /** Length of the opening "Hmm". */
        const val HMM_TICKS = 6

        /** Silence after the last letter. */
        const val TAIL_TICKS = 4

        /** Gap between two lines of a burst. */
        const val GAP_TICKS = 10

        /** Pause after two lines. */
        const val PAUSE_TICKS = 40

        /** Maximum number of waiting lines. */
        const val MAX_QUEUE = 8
    }
}
