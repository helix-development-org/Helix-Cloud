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
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
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
    private val scope: CoroutineScope
) : CommandExecutor, TabCompleter {
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
            else -> sender.sendMessage(Component.text("/iguard <panel|spectate|alerts|clients|info|history|cases|case|replay|confidence|ban|unban|bans|banhistory|status|reload>", NamedTextColor.YELLOW))
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
        val player = sender as? Player ?: return sender.sendMessage(error("Only players can toggle alerts"))
        val enabled = alerts.toggle(player.uniqueId)
        sender.sendMessage(success("IGuard alerts ${if (enabled) "enabled" else "disabled"}"))
    }

    private fun info(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.info")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(error("Usage: /iguard info <player>"))
        val player = Bukkit.getPlayerExact(name) ?: return sender.sendMessage(error("Player is not online"))
        val snapshot = api.snapshot(player.uniqueId) ?: return sender.sendMessage(error("No packet state is available yet"))
        sender.sendMessage(Component.text("IGuard: ${snapshot.playerName}", NamedTextColor.GOLD))
        sender.sendMessage(Component.text("Client ${snapshot.clientVersion}, supported=${snapshot.supported}, exempt=${api.isExempt(player.uniqueId)}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("Identity ${snapshot.clientFamily} (${snapshot.clientConfidence}), brand=${snapshot.clientBrand ?: "unknown"}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("Channels ${snapshot.clientChannels.sorted().joinToString().ifEmpty { "none observed" }}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("Drops ${snapshot.droppedPackets}, last packet ${snapshot.lastPacketAt ?: "never"}", NamedTextColor.GRAY))
        val levels = snapshot.violationLevels.filterValues { it > 0.0 }.entries.joinToString { "${it.key}=%.2f".format(it.value) }
        sender.sendMessage(Component.text("VL ${levels.ifEmpty { "none" }}", NamedTextColor.GRAY))
        api.latestIncident(player.uniqueId)?.let { incident ->
            sender.sendMessage(Component.text("Case ${incident.incidentId.toString().take(8)} confidence=${percent(incident.confidence)} families=${incident.families.joinToString()} shadow=${incident.shadowAction ?: "none"}", NamedTextColor.GRAY))
        }
    }

    private fun clients(sender: CommandSender) {
        if (!permission(sender, "iguard.clients")) return
        sender.sendMessage(Component.text("IGuard client fingerprints", NamedTextColor.GOLD))
        Bukkit.getOnlinePlayers().sortedBy(Player::getName).forEach { player ->
            val snapshot = api.snapshot(player.uniqueId)
            val identity = snapshot?.let { "${it.clientFamily} (${it.clientConfidence}), brand=${it.clientBrand ?: "unknown"}" }
                ?: "waiting for packets"
            sender.sendMessage(Component.text("${player.name}: $identity", NamedTextColor.GRAY))
        }
    }

    private fun history(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.history")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(error("Usage: /iguard history <player> [page] [server|all]"))
        val page = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val selected = args.getOrNull(3)
        val server = if (selected.equals("all", true)) null else selected ?: config.serverId
        scope.launch {
            val result = runCatching { storage.history(name, page, server) }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                result.onSuccess { entries ->
                    sender.sendMessage(Component.text("IGuard history for $name, page $page", NamedTextColor.GOLD))
                    if (entries.isEmpty()) sender.sendMessage(Component.text("No records found", NamedTextColor.GRAY))
                    entries.forEach { entry ->
                        val time = formatter.format(entry.createdAt)
                        sender.sendMessage(Component.text("$time [${entry.serverId}] ${entry.checkId} VL %.2f".format(entry.violationLevel), NamedTextColor.GRAY))
                    }
                }.onFailure { sender.sendMessage(error("History query failed: ${it.message}")) }
            })
        }
    }

    private fun cases(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.cases")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(error("Usage: /iguard cases <player> [page] [server|all]"))
        val page = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val selected = args.getOrNull(3)
        val server = if (selected.equals("all", true)) null else selected ?: config.serverId
        scope.launch {
            val result = runCatching { storage.incidents(name, page, server) }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                result.onSuccess { entries ->
                    sender.sendMessage(Component.text("IGuard cases for $name, page $page", NamedTextColor.GOLD))
                    if (entries.isEmpty()) sender.sendMessage(Component.text("No cases found", NamedTextColor.GRAY))
                    entries.forEach { incident ->
                        sender.sendMessage(
                            Component.text(
                                "${incident.incidentId} ${formatter.format(incident.updatedAt)} confidence=${percent(incident.confidence)} families=${incident.families.joinToString()} shadow=${incident.shadowAction ?: "none"}",
                                NamedTextColor.GRAY
                            )
                        )
                    }
                }.onFailure { sender.sendMessage(error("Case query failed: ${it.message}")) }
            })
        }
    }

    private fun case(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.case")) return
        val id = args.getOrNull(1)?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: return sender.sendMessage(error("Usage: /iguard case <uuid>"))
        scope.launch {
            val result = runCatching { storage.incident(id) }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                result.onSuccess { incident ->
                    if (incident == null) return@onSuccess sender.sendMessage(error("Case not found"))
                    sender.sendMessage(Component.text("IGuard case ${incident.incidentId}", NamedTextColor.GOLD))
                    sender.sendMessage(Component.text("${incident.playerName} on ${incident.serverId}, ${formatter.format(incident.openedAt)} - ${formatter.format(incident.updatedAt)}", NamedTextColor.GRAY))
                    sender.sendMessage(Component.text("confidence=${percent(incident.confidence)}, calibrated=${incident.calibrated}, evidence=${incident.evidenceCount}", NamedTextColor.GRAY))
                    sender.sendMessage(Component.text("families=${incident.families.joinToString()}, shadow=${incident.shadowAction ?: "none"}, recipe=${incident.recipeVersion}", NamedTextColor.GRAY))
                }.onFailure { sender.sendMessage(error("Case query failed: ${it.message}")) }
            })
        }
    }

    private fun replay(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.replay")) return
        val player = sender as? Player ?: return sender.sendMessage(error("Only players can watch replays"))
        when (args.getOrNull(1)?.lowercase()) {
            "stop" -> { replayService.stop(player); return }
            "pause" -> { replayService.pause(player); return }
            "speed" -> { replayService.setSpeed(player, args.getOrNull(2)?.toDoubleOrNull() ?: 1.0); return }
            "follow" -> { replayService.toggleFollow(player); return }
            "trail" -> { replayService.toggleTrail(player); return }
        }
        val id = args.getOrNull(1)?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: return sender.sendMessage(error("Usage: /iguard replay <case-uuid> [speed] | pause | speed <n> | follow | trail | stop"))
        val speed = args.getOrNull(2)?.toDoubleOrNull() ?: 1.0
        replayService.startReplay(player, id, speed)
    }

    private fun confidence(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.confidence")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(error("Usage: /iguard confidence <player>"))
        val player = Bukkit.getPlayerExact(name) ?: return sender.sendMessage(error("Player is not online"))
        val incident = api.latestIncident(player.uniqueId) ?: return sender.sendMessage(Component.text("No active case for ${player.name}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("${player.name}: confidence=${percent(incident.confidence)}, calibrated=${incident.calibrated}, families=${incident.families.joinToString()}, shadow=${incident.shadowAction ?: "none"}", NamedTextColor.GRAY))
    }

    private fun status(sender: CommandSender) {
        if (!permission(sender, "iguard.status")) return
        sender.sendMessage(Component.text("IGuard status", NamedTextColor.GOLD))
        sender.sendMessage(Component.text("Players ${engine.trackedPlayers()}, packet drops ${engine.totalDroppedPackets()}, unevaluated ${engine.unevaluatedFrameCount()}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("Worker queues ${engine.queueSizes().joinToString(prefix = "[", postfix = "]")}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("Storage ${if (storage.isAvailable()) "available" else "unavailable"}, queue ${storage.queueSize()}, written ${storage.writtenRecords()}, dropped ${storage.droppedRecords()}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("TPS ${"%.2f".format(Bukkit.getTPS().firstOrNull() ?: 20.0)}", NamedTextColor.GRAY))
        val processing = engine.processingMetrics()
        val sampling = sampler.timingMicros()
        sender.sendMessage(Component.text("Frames ${processing.first}, worker avg ${"%.1f".format(processing.second)}us max ${"%.1f".format(processing.third)}us", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("Sampler last ${"%.1f".format(sampling.first)}us max ${"%.1f".format(sampling.second)}us, players/tick ${sampler.sampledPlayers()}", NamedTextColor.GRAY))
    }

    private fun reload(sender: CommandSender) {
        if (!permission(sender, "iguard.reload")) return
        runCatching { IGuardConfig.reloadDynamic(plugin) }
            .onSuccess {
                dynamic.set(it)
                sender.sendMessage(success("Check thresholds and alert settings reloaded; infrastructure changes require a restart"))
            }
            .onFailure { sender.sendMessage(error("Reload failed: ${it.message}")) }
    }

    private fun panel(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.panel")) return
        val player = sender as? Player ?: return sender.sendMessage(error("Only players can open the panel"))
        gui.open(player, args.getOrNull(1))
    }

    private fun spectate(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.spectate")) return
        val admin = sender as? Player ?: return sender.sendMessage(error("Only players can spectate"))
        val name = args.getOrNull(1) ?: return sender.sendMessage(error("Usage: /iguard spectate <player>"))
        val target = Bukkit.getPlayerExact(name) ?: return sender.sendMessage(error("Player is not online"))
        if (!spectateService.start(admin, target)) sender.sendMessage(error("Cannot spectate that player"))
    }

    private fun unspectate(sender: CommandSender) {
        if (!permission(sender, "iguard.spectate")) return
        (sender as? Player)?.let(spectateService::stop)
    }

    private fun ban(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.ban")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(error("Usage: /iguard ban <player> [hours] [reason]"))
        val target = Bukkit.getPlayerExact(name) ?: return sender.sendMessage(error("Player is not online"))
        // Optional numeric second arg = duration in hours; everything after is the reason.
        val hoursArg = args.getOrNull(2)?.toIntOrNull()
        val hours = (hoursArg ?: config.sanctions.firstBanHours).coerceIn(1, 8760)
        val reason = args.drop(if (hoursArg != null) 3 else 2).joinToString(" ").ifBlank { "Manual ban" }
        bans.ban(target.uniqueId, target.name, hours, reason, sender.name)
        sender.sendMessage(success("Banned ${target.name} for ${hours}h"))
    }

    private fun unban(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.ban")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(error("Usage: /iguard unban <player>"))
        scope.launch {
            // Resolve the identity from IGuard's own records; for external providers where the player is
            // unknown to IGuard, fall back to an offline lookup so the delegated command still fires.
            val who = runCatching { storage.findPlayer(name) }.getOrNull()
                ?: Bukkit.getOfflinePlayer(name).let { it.uniqueId to (it.name ?: name) }
            bans.unban(who.first, who.second, sender.name)
            Bukkit.getScheduler().runTask(plugin, Runnable { sender.sendMessage(success("Unban issued for ${who.second}")) })
        }
    }

    private fun bans(sender: CommandSender) {
        if (!permission(sender, "iguard.ban")) return
        scope.launch {
            val result = runCatching { storage.activeBans() }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                result.onSuccess { list ->
                    sender.sendMessage(Component.text("IGuard active bans (${list.size})", NamedTextColor.GOLD))
                    if (list.isEmpty()) sender.sendMessage(Component.text("No active bans", NamedTextColor.GRAY))
                    list.forEach { b ->
                        val until = b.expiresAt?.let { formatter.format(java.time.Instant.ofEpochMilli(it)) } ?: "permanent"
                        sender.sendMessage(Component.text("${b.playerName} — until $until — ${b.reason}", NamedTextColor.GRAY))
                    }
                }.onFailure { sender.sendMessage(error("Ban query failed: ${it.message}")) }
            })
        }
    }

    private fun banHistory(sender: CommandSender, args: Array<out String>) {
        if (!permission(sender, "iguard.ban")) return
        val name = args.getOrNull(1) ?: return sender.sendMessage(error("Usage: /iguard banhistory <player>"))
        scope.launch {
            val result = runCatching { storage.banHistory(name) }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                result.onSuccess { list ->
                    sender.sendMessage(Component.text("IGuard punishment history for $name (${list.size})", NamedTextColor.GOLD))
                    if (list.isEmpty()) sender.sendMessage(Component.text("No punishments recorded", NamedTextColor.GRAY))
                    list.forEach { p ->
                        val dur = p.hours?.let { " ${it}h" } ?: ""
                        val color = if (p.type == "UNBAN") NamedTextColor.GREEN else NamedTextColor.RED
                        sender.sendMessage(Component.text("${formatter.format(java.time.Instant.ofEpochMilli(p.createdAt))} ${p.type}$dur by ${p.actor} — ${p.reason}", color))
                    }
                }.onFailure { sender.sendMessage(error("History query failed: ${it.message}")) }
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
        sender.sendMessage(error("You do not have permission"))
        return false
    }

    private fun success(message: String) = Component.text(message, NamedTextColor.GREEN)
    private fun error(message: String) = Component.text(message, NamedTextColor.RED)

    companion object {
        private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
    }
}

private fun percent(value: Double) = "%.0f%%".format(value * 100.0)
