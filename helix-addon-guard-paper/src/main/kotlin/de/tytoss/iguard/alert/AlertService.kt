package de.tytoss.iguard.alert

import de.tytoss.iguard.config.DynamicConfig
import de.tytoss.iguard.model.ViolationRecord
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private data class AlertKey(val playerId: UUID, val checkId: String)
private data class PendingAlert(val firstAt: Long, val count: Int, val latest: ViolationRecord)

/**
 * Batches check failures into per-player/check staff alerts (chat with hover evidence + click
 * actions, optional console line) flushed once per tick with a configurable cooldown.
 */
class AlertService(
    private val plugin: JavaPlugin,
    private val dynamic: AtomicReference<DynamicConfig>
) {
    private val pending = ConcurrentHashMap<AlertKey, PendingAlert>()
    private val subscribers = ConcurrentHashMap.newKeySet<UUID>()
    private var task: BukkitTask? = null

    /** Loads the persisted subscriber list and starts the per-tick flush task. */
    fun start() {
        loadSubscribers()
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::flush), 1L, 1L)
    }

    /** Cancels the flush task and drops any queued alerts. */
    fun stop() {
        task?.cancel()
        task = null
        pending.clear()
    }

    /** Toggles alert delivery for a staff member; returns the new state. */
    fun toggle(playerId: UUID): Boolean {
        val enabled = if (subscribers.remove(playerId)) false else subscribers.add(playerId)
        saveSubscribers()
        return enabled
    }

    /** Queues a violation for the next alert flush (coalesced per player+check). */
    fun record(record: ViolationRecord) {
        val key = AlertKey(record.playerId, record.checkId)
        pending.compute(key) { _, current ->
            if (current == null) PendingAlert(record.createdAt, 1, record)
            else PendingAlert(current.firstAt, current.count + 1, record)
        }
    }

    private fun flush() {
        val config = dynamic.get().alerts
        if (!config.enabled) {
            pending.clear()
            return
        }
        val now = System.currentTimeMillis()
        for ((key, value) in pending) {
            if (now - value.firstAt < config.cooldownMillis || !pending.remove(key, value)) continue
            val record = value.latest
            val rich = richAlert(record, value.count)
            Bukkit.getOnlinePlayers().asSequence()
                .filter { it.uniqueId in subscribers && it.hasPermission("iguard.alerts") }
                .forEach { it.sendMessage(rich) }
            if (config.console) Bukkit.getConsoleSender().sendMessage(consoleLine(record, value.count))
        }
    }

    /** A modern alert: colour-graded confidence, a hoverable evidence tooltip, and clickable actions. */
    private fun richAlert(record: ViolationRecord, count: Int): Component {
        val confPct = (record.confidence * 100.0).toInt()
        val confColor = when {
            confPct >= 80 -> NamedTextColor.RED
            confPct >= 50 -> NamedTextColor.GOLD
            else -> NamedTextColor.YELLOW
        }
        val hover = HoverEvent.showText(
            Component.text("$CHECK ${record.checkId}\n", NamedTextColor.WHITE)
                .append(Component.text("Player: ", NamedTextColor.GRAY)).append(Component.text("${record.playerName}\n", NamedTextColor.WHITE))
                .append(Component.text("VL: ", NamedTextColor.GRAY)).append(Component.text("%.2f".format(record.violationLevel), NamedTextColor.WHITE))
                .append(Component.text("   Confidence: ", NamedTextColor.GRAY)).append(Component.text("$confPct%\n", confColor))
                .append(Component.text(if (record.shadowAction != null) "Action: ${record.shadowAction}\n" else "", NamedTextColor.LIGHT_PURPLE))
                .append(Component.text("Evidence:\n", NamedTextColor.GRAY))
                .append(Component.text(record.evidence.entries.joinToString("\n") { "  ${it.key} = ${it.value}" }, NamedTextColor.DARK_GRAY))
        )
        val prefix = Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("IGuard", NamedTextColor.RED))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
        val head = Component.text(record.playerName, NamedTextColor.WHITE)
            .hoverEvent(hover)
            .clickEvent(ClickEvent.runCommand("/iguard panel ${record.playerName}"))
        val body = Component.text(" failed ", NamedTextColor.GRAY)
            .append(Component.text(record.checkId, NamedTextColor.AQUA))
            .append(Component.text(" (", NamedTextColor.DARK_GRAY))
            .append(Component.text("VL %.1f".format(record.violationLevel), NamedTextColor.GRAY))
            .append(Component.text(" · ", NamedTextColor.DARK_GRAY))
            .append(Component.text("$confPct%", confColor))
            .append(if (count > 1) Component.text(" x$count", NamedTextColor.DARK_GRAY) else Component.empty())
            .append(Component.text(")", NamedTextColor.DARK_GRAY))
            .hoverEvent(hover)
        return prefix.append(head).append(body).append(Component.text("  ")).append(actions(record.playerName))
    }

    private fun actions(player: String): Component {
        /** Renders one clickable action button. */
        fun button(label: String, color: NamedTextColor, command: String, tip: String) =
            Component.text(label, color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(tip, NamedTextColor.GRAY)))
        return button("[$EYE]", NamedTextColor.AQUA, "/iguard spectate $player", "Spectate $player")
            .append(Component.text(" "))
            .append(button("[$PANEL]", NamedTextColor.GREEN, "/iguard panel $player", "Open $player in the admin panel"))
            .append(Component.text(" "))
            .append(button("[$BAN]", NamedTextColor.RED, "/iguard ban $player", "Ban $player"))
    }

    private fun consoleLine(record: ViolationRecord, count: Int): Component {
        val details = record.evidence.entries.joinToString(" ") { "${it.key}=${it.value}" } + if (count > 1) " x$count" else ""
        return Component.text("[IGuard] ", NamedTextColor.RED)
            .append(Component.text("${record.playerName} failed ${record.checkId} (VL %.2f, %d%%) %s".format(record.violationLevel, (record.confidence * 100).toInt(), details), NamedTextColor.GRAY))
    }

    private companion object {
        const val EYE = "◉"     // spectate glyph
        const val PANEL = "≡"   // panel glyph
        const val BAN = "✘"     // ban glyph
        const val CHECK = "⚠"   // warning glyph
    }

    private fun loadSubscribers() {
        val file = plugin.dataFolder.toPath().resolve("alert-subscribers.txt")
        if (!Files.exists(file)) return
        runCatching {
            Files.readAllLines(file).mapNotNullTo(subscribers) { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
        }.onFailure { plugin.logger.warning("Could not load alert subscribers: ${it.message}") }
    }

    private fun saveSubscribers() {
        runCatching {
            Files.createDirectories(plugin.dataFolder.toPath())
            val file = plugin.dataFolder.toPath().resolve("alert-subscribers.txt")
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            Files.write(temporary, subscribers.map(UUID::toString).sorted())
            runCatching { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                .getOrElse { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
        }.onFailure { plugin.logger.warning("Could not save alert subscribers: ${it.message}") }
    }
}
