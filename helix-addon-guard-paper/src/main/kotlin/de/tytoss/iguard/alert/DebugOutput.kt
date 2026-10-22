package de.tytoss.iguard.alert

import de.tytoss.iguard.trace.DebugLine
import de.tytoss.iguard.trace.DebugRouter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delivers `/iguard debug` lines produced on the check workers to players and the console on the
 * main thread (once per tick, bounded so a debug flood can never stall a tick).
 */
class DebugOutput(private val plugin: JavaPlugin, private val router: DebugRouter) {
    private val queue = ConcurrentLinkedQueue<DebugLine>()
    private val size = AtomicInteger()
    private var task: BukkitTask? = null

    /** Hooks into the router and starts the per-tick delivery. */
    fun start() {
        router.output = { line ->
            if (size.incrementAndGet() <= MAX_QUEUED) queue.add(line) else size.decrementAndGet()
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::flush), 1L, 1L)
    }

    /** Stops delivery and drops queued lines. */
    fun stop() {
        router.output = {}
        task?.cancel()
        task = null
        queue.clear()
        size.set(0)
    }

    private fun flush() {
        var sent = 0
        while (sent < MAX_PER_TICK) {
            val line = queue.poll() ?: return
            size.decrementAndGet()
            sent++
            val message = Component.text("[IGuard-Debug] ", TextColor.color(PREFIX_COLOR))
                .append(Component.text(line.text, TextColor.color(colorOf(line))))
            if (line.viewer == DebugRouter.CONSOLE) {
                Bukkit.getConsoleSender().sendMessage(message)
            } else {
                runCatching { UUID.fromString(line.viewer) }.getOrNull()?.let(Bukkit::getPlayer)?.sendMessage(message)
            }
        }
    }

    private fun colorOf(line: DebugLine): Int = when {
        !line.counted -> HELD_COLOR
        line.violationLevel >= line.alertLevel -> ALERT_COLOR
        else -> BELOW_COLOR
    }

    private companion object {
        /** Lines delivered per tick at most. */
        const val MAX_PER_TICK = 40

        /** Lines buffered at most; further lines are dropped until the queue drains. */
        const val MAX_QUEUED = 2_000

        /** Prefix colour (design token `disabled`). */
        const val PREFIX_COLOR = 0xA89A84

        /** Failure at/above the alert level (design token `error`). */
        const val ALERT_COLOR = 0xFF5A4A

        /** Failure below the alert level (design token `warn`). */
        const val BELOW_COLOR = 0xFFB43A

        /** Failure held back by the signal cooldown (design token `parchment`). */
        const val HELD_COLOR = 0xF1E2C2
    }
}
