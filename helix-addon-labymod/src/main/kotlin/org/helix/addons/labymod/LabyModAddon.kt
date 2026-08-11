package org.helix.addons.labymod

import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource
import org.helix.api.addon.PlayerListener
import org.helix.api.player.OnlinePlayer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * LabyMod integration addon — the node side of the network's LabyMod 4
 * support.
 *
 * The actual client protocol runs in this addon's Velocity component (the
 * proxy is the only place that reliably sees the LabyMod login payload
 * behind a network). This node addon owns the configuration, collects the
 * detection reports, exposes stats and the gameplay actions (markers,
 * prompts, tab banner, NPC emotes) and feeds the component through bridge
 * values: `labymod.config`, `labymod.voicemutes`, `labymod.npcs` and the
 * one-shot queue `labymod.cmd`.
 */
class LabyModAddon : AddonBase() {
    @Volatile
    private var config: LabyConfig = LabyConfig()
    private val json = Json { ignoreUnknownKeys = true }
    private val presence = ConcurrentHashMap<String, LabyPresence>()
    private val npcEntities = ConcurrentHashMap<String, MutableMap<String, String>>()
    private val commandSeq = AtomicLong()
    private val commands = ArrayDeque<LabyCommand>()
    private var muteSync: ScheduledExecutorService? = null

    /**
     * Loads the configuration, registers actions, publishes the component
     * feed and starts the voice-mute sync.
     */
    override fun enable() {
        config = LabyConfig.load(context.storage())
        publishConfig()
        publishCommands()
        registerReportActions()
        registerPromptResponseAction()
        registerStatsActions()
        registerConfigActions()
        registerGameplayActions()
        context.registerPlayerListener(object : PlayerListener {
            override fun onLeave(player: OnlinePlayer) {
                player.uuid?.let(presence::remove)
                presence.values.removeIf { it.name.equals(player.name, ignoreCase = true) }
            }
        })
        panel(
            "labymod",
            "LabyMod",
            "/panel.html",
            "<path d=\"M12 3l7 4v6c0 4-3 7-7 8-4-1-7-4-7-8V7l7-4z\"/>" +
                "<path d=\"M9 11h.01M15 11h.01M9 15c1 1 5 1 6 0\"/>",
        )
        val sync = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "helix-labymod-mutesync").apply { isDaemon = true }
        }
        muteSync = sync
        sync.scheduleAtFixedRate(
            ::publishVoiceMutes,
            2,
            config.muteSyncIntervalSeconds.coerceAtLeast(2).toLong(),
            TimeUnit.SECONDS,
        )
    }

    /**
     * Stops the voice-mute sync.
     */
    override fun onDisable() {
        muteSync?.shutdownNow()
        muteSync = null
    }

    private fun registerReportActions() {
        action(
            "labymod.report",
            "Records a LabyMod user reported by the proxy component.",
            "labymod.report <name> <uuid> <version>",
            bridgeInvocable = true,
        ) { invocation ->
            val name = invocation.arguments.getOrNull(0)
            val uuid = invocation.arguments.getOrNull(1)
            val version = invocation.arguments.getOrNull(2) ?: "unknown"
            if (name == null || uuid == null) {
                return@action ActionResult.error("usage: labymod.report <name> <uuid> <version>")
            }
            presence[uuid] = LabyPresence(name, uuid, version, System.currentTimeMillis())
            recordLifetimeUser(uuid)
            ActionResult.ok("recorded")
        }
        action(
            "labymod.npc.entity",
            "Records the spawned entity uuid of an NPC on a backend service.",
            "labymod.npc.entity <serviceId> <npcId> <entityUuid>",
            bridgeInvocable = true,
        ) { invocation ->
            val service = invocation.arguments.getOrNull(0)
            val npcId = invocation.arguments.getOrNull(1)?.lowercase()
            val uuid = invocation.arguments.getOrNull(2)
            if (service == null || npcId == null || uuid == null) {
                return@action ActionResult.error("usage: labymod.npc.entity <serviceId> <npcId> <entityUuid>")
            }
            npcEntities.getOrPut(service) { ConcurrentHashMap() }[npcId] = uuid
            publishNpcEntities()
            ActionResult.ok("recorded")
        }
        action(
            "labymod.npc.clicked",
            "Reports an NPC interaction; plays the configured interact emotes.",
            "labymod.npc.clicked <serviceId> <npcId> <player>",
            bridgeInvocable = true,
        ) { invocation ->
            val service = invocation.arguments.getOrNull(0)
            val npcId = invocation.arguments.getOrNull(1)?.lowercase()
            if (service == null || npcId == null) {
                return@action ActionResult.error("usage: labymod.npc.clicked <serviceId> <npcId> <player>")
            }
            val emotes = config.npcs[npcId]?.interactEmotes.orEmpty()
            val uuid = npcEntities[service]?.get(npcId)
            if (config.npcEmotes && emotes.isNotEmpty() && uuid != null) {
                enqueue(
                    LabyCommand(
                        seq = 0,
                        type = "emote",
                        service = service,
                        args = listOf(uuid, emotes.joinToString(",")),
                    ),
                )
            }
            ActionResult.ok("ok")
        }
    }

    private fun registerPromptResponseAction() {
        action(
            "labymod.prompt.response",
            "Receives an input-prompt answer from the proxy component.",
            "labymod.prompt.response <player> <text...>",
            bridgeInvocable = true,
        ) { invocation ->
            val player = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: labymod.prompt.response <player> <text...>")
            val text = invocation.arguments.drop(1).joinToString(" ")
            context.publishNotification("labymod", "Input prompt answer from $player: $text")
            ActionResult.ok("recorded")
        }
    }

    private fun registerStatsActions() {
        action("labymod.list", "Lists the LabyMod users currently online.", "labymod.list") {
            val online = onlinePresences()
            if (online.isEmpty()) {
                ActionResult.ok("no LabyMod users online")
            } else {
                ActionResult.ok(*online.map { "${it.name} (${it.version})" }.toTypedArray())
            }
        }
        action("labymod.stats", "Shows LabyMod usage statistics.", "labymod.stats") {
            val online = context.onlinePlayers().size
            val laby = onlinePresences().size
            val share = if (online == 0) 0 else laby * 100 / online
            ActionResult.ok(
                "online: $laby/$online LabyMod ($share%)",
                "lifetime distinct LabyMod users: ${lifetimeUsers().size}",
            )
        }
    }

    private fun registerConfigActions() {
        action(
            "labymod.config.get",
            "Exports the LabyMod configuration as JSON.",
            "labymod.config.get",
        ) { ActionResult.ok(LabyConfig.toBridgeValue(config)) }
        action(
            "labymod.config.set",
            "Updates the LabyMod config. Keys: economy, voicemute, rpc, subtitles, npcemotes, menu, " +
                "rpcformat, mutesyncinterval.",
            "labymod.config.set <key=value>...",
        ) { invocation -> updateConfig(invocation) }
        action(
            "labymod.menu.add",
            "Adds an interaction-menu entry; {name} is the clicked player.",
            "labymod.menu.add <command> <label...>",
        ) { invocation ->
            val command = invocation.arguments.firstOrNull()
                ?: return@action ActionResult.error("usage: labymod.menu.add <command> <label...>")
            val label = invocation.arguments.drop(1).joinToString(" ")
            if (label.isBlank()) {
                return@action ActionResult.error("usage: labymod.menu.add <command> <label...>")
            }
            saveConfig(config.copy(menuEntries = config.menuEntries + MenuEntry(label, command)))
            ActionResult.ok("added entry ${config.menuEntries.size}: $label -> $command")
        }
        action(
            "labymod.menu.remove",
            "Removes an interaction-menu entry by its index (1-based).",
            "labymod.menu.remove <index>",
        ) { invocation ->
            val index = invocation.arguments.firstOrNull()?.toIntOrNull()
                ?.takeIf { it in 1..config.menuEntries.size }
                ?: return@action ActionResult.error("usage: labymod.menu.remove <1..${config.menuEntries.size}>")
            val removed = config.menuEntries[index - 1]
            saveConfig(config.copy(menuEntries = config.menuEntries.filterIndexed { i, _ -> i != index - 1 }))
            ActionResult.ok("removed: ${removed.label}")
        }
        action("labymod.menu.list", "Lists the interaction-menu entries.", "labymod.menu.list") {
            if (config.menuEntries.isEmpty()) {
                ActionResult.ok("no menu entries")
            } else {
                ActionResult.ok(
                    *config.menuEntries.mapIndexed { i, e -> "${i + 1}. ${e.label} -> ${e.command}" }
                        .toTypedArray(),
                )
            }
        }
        action(
            "labymod.npc.set",
            "Configures LabyMod emotes of an NPC; emote lists are comma-separated ids, '-' clears.",
            "labymod.npc.set <npcId> <idleIntervalSeconds> <idleEmotes|-> <interactEmotes|->",
        ) { invocation ->
            val npcId = invocation.arguments.getOrNull(0)?.lowercase()
                ?: return@action ActionResult.error(NPC_SET_USAGE)
            val interval = invocation.arguments.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 5 }
                ?: return@action ActionResult.error(NPC_SET_USAGE)
            val idle = parseEmotes(invocation.arguments.getOrNull(2))
                ?: return@action ActionResult.error(NPC_SET_USAGE)
            val interact = parseEmotes(invocation.arguments.getOrNull(3))
                ?: return@action ActionResult.error(NPC_SET_USAGE)
            val updated = if (idle.isEmpty() && interact.isEmpty()) {
                config.copy(npcs = config.npcs - npcId)
            } else {
                config.copy(
                    npcs = config.npcs +
                        (npcId to NpcEmotes(interact, idle, interval)),
                )
            }
            saveConfig(updated)
            ActionResult.ok("npc $npcId: idle=$idle every ${interval}s, interact=$interact")
        }
        action("labymod.npc.list", "Lists the NPC emote configuration.", "labymod.npc.list") {
            if (config.npcs.isEmpty()) {
                ActionResult.ok("no npc emotes configured")
            } else {
                ActionResult.ok(
                    *config.npcs.map { (id, e) ->
                        "$id: idle=${e.idleEmotes} every ${e.idleIntervalSeconds}s, interact=${e.interactEmotes}"
                    }.toTypedArray(),
                )
            }
        }
    }

    private fun registerGameplayActions() {
        action(
            "labymod.marker",
            "Shows a world marker to a LabyMod user (or all).",
            "labymod.marker <player|all> <x> <y> <z> [label...]",
        ) { invocation ->
            val target = invocation.arguments.getOrNull(0)
                ?: return@action ActionResult.error("usage: labymod.marker <player|all> <x> <y> <z> [label...]")
            val coordinates = invocation.arguments.drop(1).take(3).mapNotNull { it.toIntOrNull() }
            if (coordinates.size < 3) {
                return@action ActionResult.error("usage: labymod.marker <player|all> <x> <y> <z> [label...]")
            }
            val label = invocation.arguments.drop(4).joinToString(" ")
            enqueue(
                LabyCommand(
                    seq = 0,
                    type = "marker",
                    player = target,
                    args = coordinates.map(Int::toString) + label,
                ),
            )
            ActionResult.ok("marker queued for $target")
        }
        action(
            "labymod.prompt.input",
            "Opens an input prompt; the response is published as a `labymod` notification.",
            "labymod.prompt.input <player> <title...>",
        ) { invocation -> promptAction(invocation, "input") }
        action(
            "labymod.prompt.server",
            "Opens a server-switch prompt for another network.",
            "labymod.prompt.server <player> <address> [title...]",
        ) { invocation ->
            val player = invocation.arguments.getOrNull(0)
            val address = invocation.arguments.getOrNull(1)
            if (player == null || address == null) {
                return@action ActionResult.error("usage: labymod.prompt.server <player> <address> [title...]")
            }
            enqueue(
                LabyCommand(
                    seq = 0,
                    type = "serverswitch",
                    player = player,
                    args = listOf(address, invocation.arguments.drop(2).joinToString(" ")),
                ),
            )
            ActionResult.ok("server prompt queued for $player")
        }
        action(
            "labymod.banner",
            "Shows a tab-list banner image to a LabyMod user (or all).",
            "labymod.banner <player|all> <imageUrl>",
        ) { invocation ->
            val target = invocation.arguments.getOrNull(0)
            val url = invocation.arguments.getOrNull(1)
            if (target == null || url == null) {
                return@action ActionResult.error("usage: labymod.banner <player|all> <imageUrl>")
            }
            enqueue(LabyCommand(seq = 0, type = "banner", player = target, args = listOf(url)))
            ActionResult.ok("banner queued for $target")
        }
        action(
            "labymod.emote",
            "Plays a LabyMod emote on an NPC everywhere it is spawned.",
            "labymod.emote <npcId> <emoteId>",
        ) { invocation ->
            val npcId = invocation.arguments.getOrNull(0)?.lowercase()
            val emote = invocation.arguments.getOrNull(1)?.toIntOrNull()
            if (npcId == null || emote == null) {
                return@action ActionResult.error("usage: labymod.emote <npcId> <emoteId>")
            }
            val targets = npcEntities.filterValues { it.containsKey(npcId) }
            if (targets.isEmpty()) {
                return@action ActionResult.error("npc $npcId is not spawned on any service (yet)")
            }
            targets.forEach { (service, entities) ->
                enqueue(
                    LabyCommand(
                        seq = 0,
                        type = "emote",
                        service = service,
                        args = listOf(entities.getValue(npcId), emote.toString()),
                    ),
                )
            }
            ActionResult.ok("emote $emote queued on ${targets.size} service(s)")
        }
    }

    private fun promptAction(invocation: ActionInvocation, type: String): ActionResult {
        val player = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("usage: labymod.prompt.$type <player> <title...>")
        val title = invocation.arguments.drop(1).joinToString(" ")
        if (title.isBlank()) {
            return ActionResult.error("usage: labymod.prompt.$type <player> <title...>")
        }
        enqueue(LabyCommand(seq = 0, type = type, player = player, args = listOf(title)))
        return ActionResult.ok("$type prompt queued for $player")
    }

    private fun updateConfig(invocation: ActionInvocation): ActionResult {
        val overrides = invocation.arguments.mapNotNull { argument ->
            val parts = argument.split("=", limit = 2)
            if (parts.size == 2) parts[0].lowercase() to parts[1] else null
        }.toMap()

        /** Parses a boolean override, or null to keep the current value. */
        fun flag(key: String, current: Boolean) = overrides[key]?.toBooleanStrictOrNull() ?: current
        val updated = config.copy(
            economyHud = flag("economy", config.economyHud),
            voiceMuteSync = flag("voicemute", config.voiceMuteSync),
            discordRpc = flag("rpc", config.discordRpc),
            subtitlesSync = flag("subtitles", config.subtitlesSync),
            npcEmotes = flag("npcemotes", config.npcEmotes),
            interactionMenu = flag("menu", config.interactionMenu),
            rpcFormat = overrides["rpcformat"] ?: config.rpcFormat,
            muteSyncIntervalSeconds = overrides["mutesyncinterval"]?.toIntOrNull()
                ?: config.muteSyncIntervalSeconds,
        )
        saveConfig(updated)
        return ActionResult.ok("configuration saved")
    }

    private fun saveConfig(updated: LabyConfig) {
        config = updated
        LabyConfig.save(context.storage(), updated)
        publishConfig()
    }

    private fun publishConfig() {
        context.publishBridgeValue("labymod.config", LabyConfig.toBridgeValue(config))
    }

    private fun publishNpcEntities() {
        context.publishBridgeValue(
            "labymod.npcs",
            json.encodeToString(npcEntities.mapValues { it.value.toMap() }),
        )
    }

    private fun publishCommands() {
        val snapshot = synchronized(commands) { commands.toList() }
        context.publishBridgeValue("labymod.cmd", json.encodeToString(LabyCommandQueue(snapshot)))
    }

    private fun publishVoiceMutes() {
        if (!config.voiceMuteSync) {
            return
        }
        val result = runCatching {
            context.actions.invoke(ActionInvocation("mute.export", emptyList(), ActionSource.ADDON))
        }.getOrNull() ?: return
        if (result.success) {
            context.publishBridgeValue("labymod.voicemutes", result.lines.firstOrNull() ?: "[]")
        }
    }

    private fun enqueue(command: LabyCommand) {
        synchronized(commands) {
            commands.addLast(command.copy(seq = commandSeq.incrementAndGet()))
            while (commands.size > QUEUE_LIMIT) {
                commands.removeFirst()
            }
        }
        publishCommands()
    }

    private fun onlinePresences(): List<LabyPresence> {
        val online = context.onlinePlayers()
        val names = online.map { it.name.lowercase() }.toSet()
        val uuids = online.mapNotNull { it.uuid }.toSet()
        return presence.values
            .filter { it.uuid in uuids || it.name.lowercase() in names }
            .sortedBy { it.name.lowercase() }
    }

    private fun lifetimeUsers(): Set<String> =
        context.storage().read(STATS_DOCUMENT)
            ?.let { raw -> runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull() }
            .orEmpty()

    private fun recordLifetimeUser(uuid: String) {
        val users = lifetimeUsers()
        if (uuid !in users) {
            context.storage().write(STATS_DOCUMENT, json.encodeToString(users + uuid))
        }
    }

    private fun parseEmotes(raw: String?): List<Int>? = when {
        raw == null -> null
        raw == "-" -> emptyList()
        else -> raw.split(",").map { it.trim().toIntOrNull() ?: return null }
    }

    private companion object {
        /** Storage document holding the distinct LabyMod user uuids. */
        const val STATS_DOCUMENT = "stats"

        /** Maximum queued one-shot commands. */
        const val QUEUE_LIMIT = 25

        const val NPC_SET_USAGE =
            "usage: labymod.npc.set <npcId> <idleIntervalSeconds>=5.. <idleEmotes|-> <interactEmotes|->"
    }
}
