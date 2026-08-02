package org.helix.addons.npc.paper

import de.tytoss.inpc.INpc
import de.tytoss.inpc.npc.NpcDefinition
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.helix.api.i18n.NodeTranslations

/**
 * Helix-NPC Paper component.
 *
 * On enable it installs the vendored INpc framework, learns this server's
 * task from `HELIX_TASK`, fetches the task's NPC definitions from the
 * `helix.npc` node addon and renders them. A background poller re-syncs
 * every [SYNC_TICKS] ticks so NPCs created, edited or deleted on any server
 * of the task appear network-wide. The `/npc` admin command edits the same
 * node-owned definitions, so every change persists and propagates.
 */
class NpcPlugin : JavaPlugin() {
    private val json = Json { ignoreUnknownKeys = true }
    private val mini = MiniMessage.miniMessage()

    /** Live NPC handles by id, mutated only on the main thread. */
    private val spawned = ConcurrentHashMap<String, NpcDefinition>()

    /** Last-applied definition by id, used to detect edits during sync. */
    private val known = ConcurrentHashMap<String, NpcDef>()

    private var npcs: INpc? = null
    private var client: NodeClient? = null
    private var task: String = "*"
    private var serviceId: String = ""
    private lateinit var scope: CoroutineScope

    /** Node-backed player-facing texts (`helix.npc` language files). */
    private lateinit var translations: NodeTranslations

    /** Dispatcher running coroutines on the Bukkit main thread. */
    private val mainDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (isEnabled) {
                server.scheduler.runTask(this@NpcPlugin, block)
            }
        }
    }

    /**
     * Boots INpc, resolves the task and starts the node sync loop.
     */
    override fun onEnable() {
        val nodeClient = NodeClient.fromEnvironment()
        if (nodeClient == null) {
            logger.warning("No Helix environment found — Helix-NPC disabled.")
            server.pluginManager.disablePlugin(this)
            return
        }
        client = nodeClient
        task = System.getenv("HELIX_TASK")?.takeIf { it.isNotBlank() } ?: "*"
        serviceId = System.getenv("HELIX_SERVICE_ID").orEmpty()
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        // Same environment NodeClient.fromEnvironment() just validated.
        translations = NodeTranslations(nodeClient.controlUrl, System.getenv("HELIX_CONTROL_TOKEN").orEmpty(), "helix.npc")
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { translations.sync() }, 1L, TRANSLATION_SYNC_TICKS)
        npcs = INpc.install(this)
        logger.info("Helix-NPC installed for task '$task'.")
        // First sync shortly after start (worlds must be loaded), then poll.
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { syncTick() }, 40L, SYNC_TICKS)
    }

    /**
     * Despawns every NPC and shuts the INpc runtime down.
     */
    override fun onDisable() {
        if (::scope.isInitialized) {
            npcs?.let { runBlocking { it.shutdown() } }
            scope.cancel()
        }
        spawned.clear()
        known.clear()
        client?.close()
        client = null
        if (::translations.isInitialized) translations.close()
    }

    /**
     * Handles `/npc <sub> …`. Gated on `helix.npc.admin`.
     *
     * @param sender command sender.
     * @param command the command.
     * @param label used alias.
     * @param args subcommand and its arguments.
     * @return always `true`.
     */
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("helix.npc.admin")) {
            sender.sendMessage(chat(sender, "admin.denied"))
            return true
        }
        val sub = args.getOrNull(0)?.lowercase()
        when (sub) {
            "create" -> create(sender, args)
            "delete" -> delete(sender, args)
            "list" -> list(sender)
            "tp" -> teleport(sender, args)
            "skin" -> edit(sender, args, "skin.usage") { it.copy(skin = args[2]) }
            "look" -> edit(sender, args, "look.usage") { it.copy(lookMode = args[2].lowercase()) }
            "hologram" -> edit(sender, args, "hologram.usage") { def ->
                val raw = args.drop(2).joinToString(" ")
                val lines = if (raw == "-") emptyList() else raw.split("|").map { it.trim() }
                def.copy(hologramLines = lines)
            }
            "interact" -> edit(sender, args, "interact.usage") { def ->
                val raw = args.drop(2).joinToString(" ").trim()
                def.copy(interactAction = raw.takeIf { it.isNotEmpty() && it != "-" })
            }
            "reload" -> {
                sender.sendMessage(chat(sender, "resync"))
                server.scheduler.runTaskAsynchronously(this, Runnable { syncTick() })
            }
            else -> sender.sendMessage(chat(sender, "usage"))
        }
        return true
    }

    // --------------------------------------------------------------- commands --

    private fun create(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run {
            sender.sendMessage(chat(sender, "create.players-only"))
            return
        }
        val id = args.getOrNull(1) ?: run {
            sender.sendMessage(chat(sender, "create.usage"))
            return
        }
        val skin = args.getOrNull(2) ?: "self"
        val loc = player.location
        val def = NpcDef(
            id = id.lowercase(),
            task = task,
            world = loc.world.name,
            x = loc.x,
            y = loc.y,
            z = loc.z,
            yaw = loc.yaw,
            pitch = loc.pitch,
            skin = skin,
            hologramLines = listOf(id),
            lookMode = "none",
        )
        save(sender, def, "created")
    }

    private fun delete(sender: CommandSender, args: Array<out String>) {
        val id = args.getOrNull(1)?.lowercase() ?: run {
            sender.sendMessage(chat(sender, "delete.usage"))
            return
        }
        val nodeClient = client ?: return
        scope.launch(Dispatchers.IO) {
            nodeClient.action("npc.delete", id)
            withContext(mainDispatcher) {
                despawn(id)
                known.remove(id)
                sender.sendMessage(chat(sender, "deleted", "id" to id))
            }
        }
    }

    private fun list(sender: CommandSender) {
        if (known.isEmpty()) {
            sender.sendMessage(chat(sender, "list.empty"))
            return
        }
        sender.sendMessage(chat(sender, "list.header", "task" to task))
        known.values.sortedBy { it.id }.forEach { def ->
            sender.sendMessage(
                chat(sender, "list.entry", "id" to def.id, "skin" to def.skin, "look" to def.lookMode),
            )
        }
    }

    private fun teleport(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: return
        val id = args.getOrNull(1)?.lowercase() ?: run {
            sender.sendMessage(chat(sender, "tp.usage"))
            return
        }
        val def = known[id] ?: run {
            sender.sendMessage(chat(sender, "tp.unknown", "id" to id))
            return
        }
        val world = Bukkit.getWorld(def.world) ?: return
        player.teleport(org.bukkit.Location(world, def.x, def.y, def.z, def.yaw, def.pitch))
        sender.sendMessage(chat(sender, "tp.done", "id" to id))
    }

    /**
     * Loads an NPC's authoritative definition from the node, applies
     * [transform], persists it and re-renders it locally.
     */
    private fun edit(
        sender: CommandSender,
        args: Array<out String>,
        usageKey: String,
        transform: (NpcDef) -> NpcDef,
    ) {
        val id = args.getOrNull(1)?.lowercase()
        if (id == null || args.size < 3) {
            sender.sendMessage(chat(sender, usageKey))
            return
        }
        val nodeClient = client ?: return
        scope.launch(Dispatchers.IO) {
            val current = fetchDef(nodeClient, id)
            if (current == null) {
                withContext(mainDispatcher) { sender.sendMessage(chat(sender, "edit.unknown", "id" to id)) }
                return@launch
            }
            val updated = transform(current)
            nodeClient.action("npc.save", json.encodeToString(updated))
            withContext(mainDispatcher) {
                apply(updated)
                sender.sendMessage(chat(sender, "updated", "id" to id))
            }
        }
    }

    private fun save(sender: CommandSender, def: NpcDef, feedbackKey: String) {
        val nodeClient = client ?: return
        scope.launch(Dispatchers.IO) {
            val ok = nodeClient.action("npc.save", json.encodeToString(def)) != null
            withContext(mainDispatcher) {
                if (ok) {
                    apply(def)
                    sender.sendMessage(chat(sender, feedbackKey, "id" to def.id))
                } else {
                    sender.sendMessage(chat(sender, "rejected"))
                }
            }
        }
    }

    /** Reads an NPC's authoritative definition from the node, or `null`. */
    private fun fetchDef(nodeClient: NodeClient, id: String): NpcDef? =
        nodeClient.action("npc.get", id)?.let { runCatching { json.decodeFromString<NpcDef>(it) }.getOrNull() }

    // ------------------------------------------------------------------- sync --

    /** Fetches the task's definitions from the node and applies the diff. */
    private fun syncTick() {
        val nodeClient = client ?: return
        val body = nodeClient.action("npc.list", task) ?: return
        val defs = runCatching { json.decodeFromString<List<NpcDef>>(body) }.getOrNull() ?: return
        scope.launch { applySync(defs) }
    }

    private suspend fun applySync(defs: List<NpcDef>) {
        val incoming = defs.associateBy { it.id }
        (known.keys - incoming.keys).forEach { id ->
            despawn(id)
            known.remove(id)
        }
        incoming.values.forEach { def ->
            if (known[def.id] != def) {
                apply(def)
            }
        }
    }

    /** Re-renders a definition: despawns the previous handle and spawns fresh. */
    private suspend fun apply(def: NpcDef) {
        despawn(def.id)
        val runtime = npcs ?: return
        val world = Bukkit.getWorld(def.world)
        if (world == null) {
            logger.warning("NPC '${def.id}' references unknown world '${def.world}' — skipped.")
            return
        }
        val definition = runtime.npc(def.id) {
            location(world, def.x, def.y, def.z, def.yaw, def.pitch)
            if (def.skin.equals("self", ignoreCase = true)) {
                skin { mirrorViewer() }
            } else {
                skin(def.skin)
            }
            if (def.hologramLines.isNotEmpty()) {
                hologram { def.hologramLines.forEach { line(mini.deserialize(it)) } }
            }
            if (def.lookMode == "nearest" || def.lookMode == "player") {
                look { nearestPlayer() }
            }
            val interact = def.interactAction
            // Always registered: even command-less NPCs report their clicks so
            // integrations (LabyMod interact emotes) can react network-side.
            onInteract { ctx ->
                if (interact != null) {
                    runInteract(ctx.player, interact)
                }
                reportInteraction(def.id, ctx.player.name)
            }
        }
        definition.spawn()
        spawned[def.id] = definition
        known[def.id] = def
        reportEntity(def.id, definition)
    }

    /**
     * Reports the spawned entity uuid to the node (best effort) so the
     * LabyMod integration can address this NPC in emote packets. The uuid
     * sits on the framework's internal state and is read reflectively.
     */
    private fun reportEntity(id: String, definition: NpcDefinition) {
        if (serviceId.isBlank()) {
            return
        }
        val nodeClient = client ?: return
        val uuid = runCatching {
            val state = definition.javaClass.getMethod("getState\$inpc").invoke(definition)
            state.javaClass.getMethod("getUuid").invoke(state)?.toString()
        }.getOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            nodeClient.action("labymod.npc.entity", serviceId, id, uuid)
        }
    }

    /** Reports an NPC click to the node (best effort), off the main thread. */
    private fun reportInteraction(id: String, player: String) {
        if (serviceId.isBlank()) {
            return
        }
        val nodeClient = client ?: return
        scope.launch(Dispatchers.IO) {
            nodeClient.action("labymod.npc.clicked", serviceId, id, player)
        }
    }

    private suspend fun despawn(id: String) {
        spawned.remove(id)?.remove()
    }

    /** Runs an NPC's interact command as the clicking player, on the main thread. */
    private fun runInteract(player: Player, action: String) {
        val command = action.removePrefix("/")
        scope.launch { player.performCommand(command) }
    }

    private fun render(markup: String): Component = mini.deserialize(markup)

    /**
     * A chat message resolved in the sender's language (with the network
     * prefix, per [NodeTranslations.text]) and rendered as MiniMessage.
     * Console senders resolve to the network's default language.
     */
    private fun chat(sender: CommandSender, key: String, vararg params: Pair<String, String>): Component =
        render(translations.text(sender.name, (sender as? Player)?.locale()?.language, key, *params))

    private companion object {
        /** Sync poll interval in ticks (20 ticks = 1 second). */
        const val SYNC_TICKS = 200L

        /** How often the translation snapshot re-syncs from the node. */
        const val TRANSLATION_SYNC_TICKS = 100L
    }
}
