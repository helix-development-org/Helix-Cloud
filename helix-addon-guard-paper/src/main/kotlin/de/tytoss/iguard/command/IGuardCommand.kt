package de.tytoss.iguard.command

import de.tytoss.iguard.api.IGuardApi
import de.tytoss.iguard.alert.AlertService
import de.tytoss.iguard.check.CheckEngine
import de.tytoss.iguard.config.DynamicConfig
import de.tytoss.iguard.config.IGuardConfig
import de.tytoss.iguard.storage.GuardStore
import de.tytoss.iguard.snapshot.MainThreadSampler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.i18n.NodeTranslations
import org.helix.api.message.LegacyToMini
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/** `/iguard` command handler (status, history, cases, replays, bans, panel, reload) + tab completion. */
class IGuardCommand(
    private val plugin: JavaPlugin,
    private val config: IGuardConfig,
    private val dynamic: AtomicReference<DynamicConfig>,
    private val api: IGuardApi,
    private val engine: CheckEngine,
    private val alerts: AlertService,
    private val storage: GuardStore,
    private val sampler: MainThreadSampler,
    private val spectateService: de.tytoss.iguard.spectate.SpectateService,
    private val gui: de.tytoss.iguard.gui.GuiService,
    private val replayService: de.tytoss.iguard.replay.ReplayService,
    private val bans: de.tytoss.iguard.ban.BanCoordinator,
    private val scope: CoroutineScope,
    private val translations: NodeTranslations
) : CommandExecutor, TabCompleter {
    private val miniMessage = MiniMessage.miniMessage()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "alerts" -> alerts(sender)
            "info" -> info(sender, args)
            "clients" -> clients(sender)
            "history" -> history(sender, args)
            "cases" -> cases(sender, args)
            "case" -> case(sender, args)
            "replay" -> replay(sender, args)
            "confidence" -> confidence(sender, args)
            "status" -> status(sender)
            "reload" -> reload(sender)
            "panel" -> panel(sender, args)
            "spectate" -> spectate(sender, args)
            "unspectate" -> unspectate(sender)
            "ban" -> ban(sender, args)
            "unban" -> unban(sender, args)
            "bans" -> bans(sender)
            "banhistory" -> banHistory(sender, args)
            else -> sender.sendMessage(chat(sender, "cmd.usage"))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("panel", "spectate", "unspectate", "alerts", "clients", "info", "history", "cases", "case", "replay", "confidence", "ban", "unban", "bans", "banhistory", "status", "reload")
                .filter { it.startsWith(args[0], true) && sender.hasPermission("iguard.${permFor(it)}") }
        }
        if (args.size == 2 && args[0].lowercase() in setOf("info", "history", "cases", "confidence", "panel", "spectate", "ban", "unban", "banhistory")) {
            return Bukkit.getOnlinePlayers().map(Player::getName).filter { it.startsWith(args[1], true) }
        }
        if (args.size == 4 && args[0].equals("history", true)) return listOf(config.serverId, "all").filter { it.startsWith(args[3], true) }
        if (args.size == 2 && args[0].equals("replay", true)) return listOf("pause", "speed", "follow", "trail", "stop").filter { it.startsWith(args[1], true) }
        return emptyList()
    }

    private fun alerts(sender: CommandSender) {
        if (!permission(sender, "iguard.alerts")) return
        val player = sender as? Player ?: return sender.sendMessage(chat(sender, "cmd.alerts.players-only"))
        val enabled = alerts.toggle(player.uniqueId)
        sender.sendMessage(chat(sender, if (enabled) "cmd.alerts.enabled" else "cmd.alerts.disabled"))
    }

    private fun info(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.info")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(chat(sender, "cmd.info.usage"))
        val player = Bukkit.getPlayerExact(name) ?: return sender.sendMessage(chat(sender, "cmd.not-online"))
        val snapshot = api.snapshot(player.uniqueId) ?: return sender.sendMessage(chat(sender, "cmd.info.no-state"))
        sender.sendMessage(chat(sender, "cmd.info.header", "player" to snapshot.playerName))
        sender.sendMessage(chat(sender, "cmd.info.client",
            "version" to "${snapshot.clientVersion}", "supported" to "${snapshot.supported}", "exempt" to "${api.isExempt(player.uniqueId)}"))
        sender.sendMessage(chat(sender, "cmd.info.identity",
            "family" to snapshot.clientFamily, "confidence" to "${snapshot.clientConfidence}", "brand" to (snapshot.clientBrand ?: value(sender, "value.unknown"))))
        sender.sendMessage(chat(sender, "cmd.info.channels",
            "channels" to snapshot.clientChannels.sorted().joinToString().ifEmpty { value(sender, "value.none-observed") }))
        sender.sendMessage(chat(sender, "cmd.info.drops",
            "drops" to "${snapshot.droppedPackets}", "last" to (snapshot.lastPacketAt?.toString() ?: value(sender, "value.never"))))
        val levels = snapshot.violationLevels.filterValues { it > 0.0 }.entries.joinToString { "${it.key}=%.2f".format(it.value) }
        sender.sendMessage(chat(sender, "cmd.info.vl", "levels" to levels.ifEmpty { value(sender, "value.none") }))
        api.latestIncident(player.uniqueId)?.let { incident ->
            sender.sendMessage(chat(sender, "cmd.info.case",
                "id" to incident.incidentId.toString().take(8), "confidence" to percent(incident.confidence),
                "families" to incident.families.joinToString(), "shadow" to (incident.shadowAction ?: value(sender, "value.none"))))
        }
    }

    private fun clients(sender: CommandSender) {
        if (!permission(sender, "iguard.clients")) return
        sender.sendMessage(chat(sender, "cmd.clients.header"))
        Bukkit.getOnlinePlayers().sortedBy(Player::getName).forEach { player ->
            val snapshot = api.snapshot(player.uniqueId)
            val identity = snapshot?.let {
                value(sender, "cmd.clients.identity", "family" to it.clientFamily, "confidence" to "${it.clientConfidence}", "brand" to (it.clientBrand ?: value(sender, "value.unknown")))
            } ?: value(sender, "value.waiting-packets")
            sender.sendMessage(chat(sender, "cmd.clients.entry", "player" to player.name, "identity" to identity))
        }
    }

    private fun history(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.history")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(chat(sender, "cmd.history.usage"))
        val page = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val selected = args.getOrNull(3)
        val server = if (selected.equals("all", true)) null else selected ?: config.serverId
        scope.launch {
            val result = runCatching { storage.history(name, page, server) }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                result.onSuccess { entries ->
                    sender.sendMessage(chat(sender, "cmd.history.header", "player" to name, "page" to "$page"))
                    if (entries.isEmpty()) sender.sendMessage(chat(sender, "cmd.history.empty"))
                    entries.forEach { entry ->
                        sender.sendMessage(chat(sender, "cmd.history.entry",
                            "time" to formatter.format(entry.createdAt), "server" to entry.serverId,
                            "check" to entry.checkId, "vl" to "%.2f".format(entry.violationLevel)))
                    }
                }.onFailure { sender.sendMessage(chat(sender, "cmd.history.failed", "error" to "${it.message}")) }
            })
        }
    }

    private fun cases(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.cases")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(chat(sender, "cmd.cases.usage"))
        val page = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val selected = args.getOrNull(3)
        val server = if (selected.equals("all", true)) null else selected ?: config.serverId
        scope.launch {
            val result = runCatching { storage.incidents(name, page, server) }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                result.onSuccess { entries ->
                    sender.sendMessage(chat(sender, "cmd.cases.header", "player" to name, "page" to "$page"))
                    if (entries.isEmpty()) sender.sendMessage(chat(sender, "cmd.cases.empty"))
                    entries.forEach { incident ->
                        sender.sendMessage(chat(sender, "cmd.cases.entry",
                            "id" to "${incident.incidentId}", "time" to formatter.format(incident.updatedAt),
                            "confidence" to percent(incident.confidence), "families" to incident.families.joinToString(),
                            "shadow" to (incident.shadowAction ?: value(sender, "value.none"))))
                    }
                }.onFailure { sender.sendMessage(chat(sender, "cmd.cases.failed", "error" to "${it.message}")) }
            })
        }
    }

    private fun case(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.case")) return
        val id = args.getOrNull(1)?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: return sender.sendMessage(chat(sender, "cmd.case.usage"))
        scope.launch {
            val result = runCatching { storage.incident(id) }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                result.onSuccess { incident ->
                    if (incident == null) return@onSuccess sender.sendMessage(chat(sender, "cmd.case.not-found"))
                    sender.sendMessage(chat(sender, "cmd.case.header", "id" to "${incident.incidentId}"))
                    sender.sendMessage(chat(sender, "cmd.case.line1",
                        "player" to incident.playerName, "server" to incident.serverId,
                        "opened" to formatter.format(incident.openedAt), "updated" to formatter.format(incident.updatedAt)))
                    sender.sendMessage(chat(sender, "cmd.case.line2",
                        "confidence" to percent(incident.confidence), "calibrated" to "${incident.calibrated}", "evidence" to "${incident.evidenceCount}"))
                    sender.sendMessage(chat(sender, "cmd.case.line3",
                        "families" to incident.families.joinToString(), "shadow" to (incident.shadowAction ?: value(sender, "value.none")), "recipe" to "${incident.recipeVersion}"))
                }.onFailure { sender.sendMessage(chat(sender, "cmd.cases.failed", "error" to "${it.message}")) }
            })
        }
    }

    private fun replay(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.replay")) return
        val player = sender as? Player ?: return sender.sendMessage(chat(sender, "cmd.replay.players-only"))
        when (args.getOrNull(1)?.lowercase()) {
            "stop" -> { replayService.stop(player); return }
            "pause" -> { replayService.pause(player); return }
            "speed" -> { replayService.setSpeed(player, args.getOrNull(2)?.toDoubleOrNull() ?: 1.0); return }
            "follow" -> { replayService.toggleFollow(player); return }
            "trail" -> { replayService.toggleTrail(player); return }
        }
        val id = args.getOrNull(1)?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: return sender.sendMessage(chat(sender, "cmd.replay.usage"))
        val speed = args.getOrNull(2)?.toDoubleOrNull() ?: 1.0
        replayService.startReplay(player, id, speed)
    }

    private fun confidence(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.confidence")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(chat(sender, "cmd.confidence.usage"))
        val player = Bukkit.getPlayerExact(name) ?: return sender.sendMessage(chat(sender, "cmd.not-online"))
        val incident = api.latestIncident(player.uniqueId) ?: return sender.sendMessage(chat(sender, "cmd.confidence.none", "player" to player.name))
        sender.sendMessage(chat(sender, "cmd.confidence.line",
            "player" to player.name, "confidence" to percent(incident.confidence), "calibrated" to "${incident.calibrated}",
            "families" to incident.families.joinToString(), "shadow" to (incident.shadowAction ?: value(sender, "value.none"))))
    }

    private fun status(sender: CommandSender) {
        if (!permission(sender, "iguard.status")) return
        sender.sendMessage(chat(sender, "cmd.status.header"))
        sender.sendMessage(chat(sender, "cmd.status.players",
            "players" to "${engine.trackedPlayers()}", "drops" to "${engine.totalDroppedPackets()}", "unevaluated" to "${engine.unevaluatedFrameCount()}"))
        sender.sendMessage(chat(sender, "cmd.status.queues", "queues" to engine.queueSizes().joinToString(prefix = "[", postfix = "]")))
        sender.sendMessage(chat(sender, "cmd.status.storage",
            "state" to value(sender, if (storage.isAvailable()) "value.available" else "value.unavailable"),
            "queue" to "${storage.queueSize()}", "written" to "${storage.writtenRecords()}", "dropped" to "${storage.droppedRecords()}"))
        sender.sendMessage(chat(sender, "cmd.status.tps", "tps" to "%.2f".format(Bukkit.getTPS().firstOrNull() ?: 20.0)))
        val processing = engine.processingMetrics()
        val sampling = sampler.timingMicros()
        sender.sendMessage(chat(sender, "cmd.status.frames",
            "frames" to "${processing.first}", "avg" to "%.1f".format(processing.second), "max" to "%.1f".format(processing.third)))
        sender.sendMessage(chat(sender, "cmd.status.sampler",
            "last" to "%.1f".format(sampling.first), "max" to "%.1f".format(sampling.second), "players" to "${sampler.sampledPlayers()}"))
    }

    private fun reload(sender: CommandSender) {
        if (!permission(sender, "iguard.reload")) return
        runCatching { IGuardConfig.reloadDynamic(plugin) }
            .onSuccess {
                dynamic.set(it)
                sender.sendMessage(chat(sender, "cmd.reload.success"))
            }
            .onFailure { sender.sendMessage(chat(sender, "cmd.reload.failed", "error" to "${it.message}")) }
    }

    private fun panel(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.panel")) return
        val player = sender as? Player ?: return sender.sendMessage(chat(sender, "cmd.panel.players-only"))
        gui.open(player, args.getOrNull(1))
    }

    private fun spectate(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.spectate")) return
        val admin = sender as? Player ?: return sender.sendMessage(chat(sender, "cmd.spectate.players-only"))
        val name = args.getOrNull(1) ?: return sender.sendMessage(chat(sender, "cmd.spectate.usage"))
        val target = Bukkit.getPlayerExact(name) ?: return sender.sendMessage(chat(sender, "cmd.not-online"))
        if (!spectateService.start(admin, target)) sender.sendMessage(chat(sender, "cmd.spectate.cannot"))
    }

    private fun unspectate(sender: CommandSender) {
        if (!permission(sender, "iguard.spectate")) return
        (sender as? Player)?.let(spectateService::stop)
    }

    private fun ban(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.ban")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(chat(sender, "cmd.ban.usage"))
        val target = Bukkit.getPlayerExact(name) ?: return sender.sendMessage(chat(sender, "cmd.not-online"))
        // Optional numeric second arg = duration in hours; everything after is the reason.
        val hoursArg = args.getOrNull(2)?.toIntOrNull()
        val hours = (hoursArg ?: config.sanctions.firstBanHours).coerceIn(1, 8760)
        val reason = args.drop(if (hoursArg != null) 3 else 2).joinToString(" ").ifBlank { "Manual ban" }
        bans.ban(target.uniqueId, target.name, hours, reason, sender.name)
        sender.sendMessage(chat(sender, "cmd.ban.success", "player" to target.name, "hours" to "$hours"))
    }

    private fun unban(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.ban")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(chat(sender, "cmd.unban.usage"))
        scope.launch {
            // Resolve the identity from IGuard's own records; for external providers where the player is
            // unknown to IGuard, fall back to an offline lookup so the delegated command still fires.
            val who = runCatching { storage.findPlayer(name) }.getOrNull()
                ?: Bukkit.getOfflinePlayer(name).let { it.uniqueId to (it.name ?: name) }
            bans.unban(who.first, who.second, sender.name)
            Bukkit.getScheduler().runTask(plugin, Runnable { sender.sendMessage(chat(sender, "cmd.unban.success", "player" to who.second)) })
        }
    }

    private fun bans(sender: CommandSender) {
        if (!permission(sender, "iguard.ban")) return
        scope.launch {
            val result = runCatching { storage.activeBans() }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                result.onSuccess { list ->
                    sender.sendMessage(chat(sender, "cmd.bans.header", "count" to "${list.size}"))
                    if (list.isEmpty()) sender.sendMessage(chat(sender, "cmd.bans.empty"))
                    list.forEach { b ->
                        val until = b.expiresAt?.let { formatter.format(java.time.Instant.ofEpochMilli(it)) } ?: value(sender, "ban.expiry.permanent")
                        sender.sendMessage(chat(sender, "cmd.bans.entry", "player" to b.playerName, "until" to until, "reason" to b.reason))
                    }
                }.onFailure { sender.sendMessage(chat(sender, "cmd.bans.failed", "error" to "${it.message}")) }
            })
        }
    }

    private fun banHistory(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.ban")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(chat(sender, "cmd.banhistory.usage"))
        scope.launch {
            val result = runCatching { storage.banHistory(name) }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                result.onSuccess { list ->
                    sender.sendMessage(chat(sender, "cmd.banhistory.header", "player" to name, "count" to "${list.size}"))
                    if (list.isEmpty()) sender.sendMessage(chat(sender, "cmd.banhistory.empty"))
                    list.forEach { p ->
                        val dur = p.hours?.let { " ${it}h" } ?: ""
                        val colorTag = if (p.type == "UNBAN") "<green>" else "<red>"
                        sender.sendMessage(chat(sender, "cmd.banhistory.entry",
                            "color" to colorTag, "time" to formatter.format(java.time.Instant.ofEpochMilli(p.createdAt)),
                            "type" to p.type, "dur" to dur, "actor" to p.actor, "reason" to p.reason))
                    }
                }.onFailure { sender.sendMessage(chat(sender, "cmd.history.failed", "error" to "${it.message}")) }
            })
        }
    }

    /** Maps a subcommand to its permission node (several share one). */
    private fun permFor(sub: String): String = when (sub) {
        "spectate", "unspectate" -> "spectate"
        "unban", "bans", "banhistory" -> "ban"
        else -> sub
    }

    private fun permission(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission)) return true
        sender.sendMessage(chat(sender, "cmd.no-permission"))
        return false
    }

    /** The client language of a [Player] sender, or `null` for the console (uses the default language). */
    private fun localeOf(sender: CommandSender): String? = (sender as? Player)?.locale()?.language

    /**
     * A chat message (network prefix included) resolved in the sender's
     * language and rendered; the console gets the default language.
     */
    private fun chat(sender: CommandSender, key: String, vararg params: Pair<String, String>): Component =
        render(translations.text(sender.name, localeOf(sender), key, *params))

    /**
     * Prefix-free text resolved in the sender's language, as a plain string
     * — for values embedded into other messages (never a standalone chat line).
     */
    private fun value(sender: CommandSender, key: String, vararg params: Pair<String, String>): String =
        translations.screen(sender.name, localeOf(sender), key, *params)

    private fun render(text: String): Component =
        miniMessage.deserialize(LegacyToMini.translate(text)).decoration(TextDecoration.ITALIC, false)

    companion object {
        private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
    }
}

private fun percent(value: Double) = "%.0f%%".format(value * 100.0)
