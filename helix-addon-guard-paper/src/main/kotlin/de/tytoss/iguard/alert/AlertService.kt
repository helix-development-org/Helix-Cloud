package de.tytoss.iguard.alert

import de.tytoss.iguard.config.DynamicConfig
import de.tytoss.iguard.model.ViolationRecord
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.helix.api.i18n.NodeTranslations
import org.helix.api.message.LegacyToMini
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private data class AlertKey(val playerId: UUID, val checkId: String)

private data class PendingAlert(val firstAt: Long, val count: Int, val latest: ViolationRecord)

/**
 * Batches check failures into per-player/check staff alerts (chat with hover evidence + click
 * actions, optional console line) flushed once per tick with a configurable cooldown.
 */
class AlertService(
    private val plugin: JavaPlugin,
    private val dynamic: AtomicReference<DynamicConfig>,
    private val translations: NodeTranslations,
) {
    private val miniMessage = MiniMessage.miniMessage()
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
            Bukkit.getOnlinePlayers().asSequence()
                .filter { it.uniqueId in subscribers && it.hasPermission("iguard.alerts") }
                .forEach { it.sendMessage(richAlert(it, record, value.count)) }
            if (config.console) Bukkit.getConsoleSender().sendMessage(consoleLine(record, value.count))
        }
    }

    /**
     * A modern alert resolved in the recipient's language: colour-graded confidence, a hoverable
     * evidence tooltip, and clickable actions.
     */
    private fun richAlert(recipient: Player, record: ViolationRecord, count: Int): Component {
        val confPct = (record.confidence * 100.0).toInt()
        val confTag = when {
            confPct >= 80 -> "<red>"
            confPct >= 50 -> "<gold>"
            else -> "<yellow>"
        }
        val actionLine = record.shadowAction?.let {
            screen(recipient, "alert.hover.action", "action" to it)
        } ?: ""
        val evidence = record.evidence.entries.joinToString("\n") { "  ${it.key} = ${it.value}" }
        val hover = HoverEvent.showText(
            render(
                screen(
                    recipient, "alert.hover",
                    "check" to record.checkId,
                    "player" to record.playerName,
                    "vl" to "%.2f".format(record.violationLevel),
                    "confcolor" to confTag,
                    "conf" to "$confPct",
                    "action" to actionLine,
                    "evidence" to evidence,
                ),
            ),
        )
        val countTag = if (count > 1) "<dark_gray> x$count</dark_gray>" else ""
        val prefix = render(screen(recipient, "alert.prefix"))
        val head = Component.text(record.playerName, NamedTextColor.WHITE)
            .hoverEvent(hover)
            .clickEvent(ClickEvent.runCommand("/iguard panel ${record.playerName}"))
        val body = render(
            screen(
                recipient, "alert.body",
                "check" to record.checkId,
                "vl" to "%.1f".format(record.violationLevel),
                "confcolor" to confTag,
                "conf" to "$confPct",
                "count" to countTag,
            ),
        ).hoverEvent(hover)
        return prefix.append(head).append(body).append(Component.text("  ")).append(actions(recipient, record.playerName))
    }

    private fun actions(recipient: Player, player: String): Component {
        /** Renders one clickable action button; the hover tip is resolved prefix-free. */
        fun button(label: String, color: NamedTextColor, command: String, tipKey: String) =
            Component.text(label, color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(render(screen(recipient, tipKey, "player" to player))))
        return button("[$EYE]", NamedTextColor.AQUA, "/iguard spectate $player", "alert.button.spectate")
            .append(Component.text(" "))
            .append(button("[$PANEL]", NamedTextColor.GREEN, "/iguard panel $player", "alert.button.panel"))
            .append(Component.text(" "))
            .append(button("[$BAN]", NamedTextColor.RED, "/iguard ban $player", "alert.button.ban"))
    }

    private fun locale(player: Player): String = player.locale().language

    /** Prefix-free text resolved in the player's language (raw, for embedding or rendering). */
    private fun screen(player: Player, key: String, vararg params: Pair<String, String>): String =
        translations.screen(player.name, locale(player), key, *params)

    private fun render(text: String): Component =
        miniMessage.deserialize(LegacyToMini.translate(text)).decoration(TextDecoration.ITALIC, false)

    private fun consoleLine(record: ViolationRecord, count: Int): Component {
        val details = record.evidence.entries.joinToString(" ") { "${it.key}=${it.value}" } + if (count > 1) " x$count" else ""
        return Component.text("[IGuard] ", NamedTextColor.RED)
            .append(Component.text("${record.playerName} failed ${record.checkId} (VL %.2f, %d%%) %s".format(record.violationLevel, (record.confidence * 100).toInt(), details), NamedTextColor.GRAY))
    }

    private companion object {
        const val EYE = "◉" // spectate glyph
        const val PANEL = "≡" // panel glyph
        const val BAN = "✘" // ban glyph
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
