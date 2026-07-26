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
    private lateinit var scope: CoroutineScope

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
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
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
            sender.sendMessage(render("<red>You lack helix.npc.admin."))
            return true
        }
        val sub = args.getOrNull(0)?.lowercase()
        when (sub) {
            "create" -> create(sender, args)
            "delete" -> delete(sender, args)
            "list" -> list(sender)
            "tp" -> teleport(sender, args)
            "skin" -> edit(sender, args, "skin <id> <name|self>") { it.copy(skin = args[2]) }
            "look" -> edit(sender, args, "look <id> <none|nearest|player>") { it.copy(lookMode = args[2].lowercase()) }
            "hologram" -> edit(sender, args, "hologram <id> <line…|-> (use | for extra lines)") { def ->
                val raw = args.drop(2).joinToString(" ")
                val lines = if (raw == "-") emptyList() else raw.split("|").map { it.trim() }
                def.copy(hologramLines = lines)
            }
            "interact" -> edit(sender, args, "interact <id> <command…|->") { def ->
                val raw = args.drop(2).joinToString(" ").trim()
                def.copy(interactAction = raw.takeIf { it.isNotEmpty() && it != "-" })
            }
            "reload" -> {
                sender.sendMessage(render("<gray>Re-syncing NPCs from the node…"))
                server.scheduler.runTaskAsynchronously(this, Runnable { syncTick() })
            }
            else -> sender.sendMessage(
                render("<gray>/npc <create|delete|list|tp|skin|hologram|look|interact|reload>"),
            )
        }
        return true
    }

    // --------------------------------------------------------------- commands --

    private fun create(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run {
            sender.sendMessage(render("<red>Only players can place NPCs."))
            return
        }
        val id = args.getOrNull(1) ?: run {
            sender.sendMessage(render("<gray>/npc create <id> [skin|self]"))
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
        save(sender, def, "<green>Created NPC <white>${def.id}</white>.")
    }

    private fun delete(sender: CommandSender, args: Array<out String>) {
        val id = args.getOrNull(1)?.lowercase() ?: run {
            sender.sendMessage(render("<gray>/npc delete <id>"))
            return
        }
        val nodeClient = client ?: return
        scope.launch(Dispatchers.IO) {
            nodeClient.action("npc.delete", id)
            withContext(mainDispatcher) {
                despawn(id)
                known.remove(id)
                sender.sendMessage(render("<yellow>Deleted NPC <white>$id</white>."))
            }
        }
    }

    private fun list(sender: CommandSender) {
        if (known.isEmpty()) {
            sender.sendMessage(render("<gray>No NPCs on this task yet."))
            return
        }
        sender.sendMessage(render("<gray>NPCs on task <white>$task</white>:"))
        known.values.sortedBy { it.id }.forEach { def ->
            sender.sendMessage(
                render("<gray>• <white>${def.id}</white> <dark_gray>(${def.skin}, ${def.lookMode})</dark_gray>"),
            )
        }
    }

    private fun teleport(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: return
        val id = args.getOrNull(1)?.lowercase() ?: run {
            sender.sendMessage(render("<gray>/npc tp <id>"))
            return
        }
        val def = known[id] ?: run {
            sender.sendMessage(render("<red>Unknown NPC $id (not on this task?)."))
            return
        }
        val world = Bukkit.getWorld(def.world) ?: return
        player.teleport(org.bukkit.Location(world, def.x, def.y, def.z, def.yaw, def.pitch))
        sender.sendMessage(render("<gray>Teleported to <white>$id</white>."))
    }

    /**
     * Loads an NPC's authoritative definition from the node, applies
     * [transform], persists it and re-renders it locally.
     */
    private fun edit(
        sender: CommandSender,
        args: Array<out String>,
        usage: String,
        transform: (NpcDef) -> NpcDef,
    ) {
        val id = args.getOrNull(1)?.lowercase()
        if (id == null || args.size < 3) {
            sender.sendMessage(render("<gray>/npc $usage"))
            return
        }
        val nodeClient = client ?: return
        scope.launch(Dispatchers.IO) {
            val current = fetchDef(nodeClient, id)
            if (current == null) {
                withContext(mainDispatcher) { sender.sendMessage(render("<red>Unknown NPC $id.")) }
                return@launch
            }
            val updated = transform(current)
            nodeClient.action("npc.save", json.encodeToString(updated))
            withContext(mainDispatcher) {
                apply(updated)
                sender.sendMessage(render("<green>Updated NPC <white>$id</white>."))
            }
        }
    }

    private fun save(sender: CommandSender, def: NpcDef, feedback: String) {
        val nodeClient = client ?: return
        scope.launch(Dispatchers.IO) {
            val ok = nodeClient.action("npc.save", json.encodeToString(def)) != null
            withContext(mainDispatcher) {
                if (ok) {
                    apply(def)
                    sender.sendMessage(render(feedback))
                } else {
                    sender.sendMessage(render("<red>The node rejected the NPC (check limits/validation)."))
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
            if (interact != null) {
                onInteract { ctx -> runInteract(ctx.player, interact) }
            }
        }
        definition.spawn()
        spawned[def.id] = definition
        known[def.id] = def
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

    private companion object {
        /** Sync poll interval in ticks (20 ticks = 1 second). */
        const val SYNC_TICKS = 200L
    }
}
