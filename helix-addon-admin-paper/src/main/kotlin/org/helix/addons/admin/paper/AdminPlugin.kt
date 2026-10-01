package org.helix.addons.admin.paper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Item
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import org.helix.api.action.ActionInvocation
import org.helix.wire.ServiceNodeApi
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Helix Admin paper component – the server side of the in-game admin panel.
 *
 *  - Talks to the Helix Admin client mod on plugin channel `helix:admin` (one JSON object per message):
 *    `hello` → `welcome {enabled}`, `open` → one-time login `ticket {url}` obtained from the node
 *    (`POST /internal/panel-ticket`) or `denied {reason: permission|unreachable}` – the permission is checked at
 *    open time, after the bridge synced the player's permissions – and `call {id, op, args}` → `result {id, ok,
 *    data|error}`.
 *  - Executes the in-game ops of [StaffOps] with a permission check on every call: noclip, heal, fly, vanish,
 *    game mode, teleport, time/weather, clearing entities; per player go-to, bring, spectate, freeze, heal,
 *    game mode, inventory. Targets on other servers are reached through the addon's job queue: the actor is
 *    sent there (BungeeCord `Connect`) and the target's server finishes the action on arrival.
 *  - Reports its players (position, health, game mode, ping, frozen/vanished) to the node every 3 s, so the
 *    panel shows the whole network, and polls jobs addressed to this service every second.
 */
class AdminPlugin : JavaPlugin(), Listener, PluginMessageListener {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var api: ServiceNodeApi
    private lateinit var serviceId: String
    private lateinit var httpUrl: String
    private lateinit var token: String
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @Volatile private var panelUrl: String? = null
    private var jobCursor = -1L

    private val frozen = ConcurrentHashMap.newKeySet<UUID>()
    private val vanished = ConcurrentHashMap.newKeySet<UUID>()
    private val previousMode = ConcurrentHashMap<UUID, GameMode>()
    private val arrivals = ConcurrentHashMap<String, Arrival>()

    /** A player expected to join this server who must be placed on arrival (cross-server go-to/bring). */
    private data class Arrival(val to: String?, val mode: String, val expiresAtMs: Long)

    /** Connects to the node, registers the channels and starts presence, bridge-value and job polling. */
    override fun onEnable() {
        val controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty()
        token = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        serviceId = System.getenv("HELIX_SERVICE_ID").orEmpty()
        if (controlUrl.isBlank() || token.isBlank() || serviceId.isBlank()) {
            logger.severe("HelixAdmin requires HELIX_CONTROL_URL, HELIX_CONTROL_TOKEN and HELIX_SERVICE_ID")
            server.pluginManager.disablePlugin(this)
            return
        }
        httpUrl = (System.getenv("HELIX_CONTROL_HTTP_URL")?.ifBlank { null } ?: controlUrl).trimEnd('/')
        api = ServiceNodeApi(controlUrl, httpUrl, serviceId, token) { logger.warning(it) }.also { it.start() }

        server.messenger.registerIncomingPluginChannel(this, CHANNEL, this)
        server.messenger.registerOutgoingPluginChannel(this, CHANNEL)
        server.messenger.registerOutgoingPluginChannel(this, BUNGEE_CHANNEL)
        server.pluginManager.registerEvents(this, this)

        server.scheduler.runTaskTimer(this, Runnable { reportPresence() }, 40L, 60L)
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { panelUrl = api.bridgeValues()?.get("admin.panel_url")?.ifBlank { null } }, 20L, 100L)
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { pollJobs() }, 20L, 20L)
    }

    /** Restores everyone's game mode and releases the node connection. */
    override fun onDisable() {
        previousMode.forEach { (id, mode) -> Bukkit.getPlayer(id)?.gameMode = mode }
        if (::api.isInitialized) api.close()
    }

    // ------------------------------------------------------------------ channel

    /**
     * Handles a message of the client mod.
     *
     * @param channel the channel (`helix:admin`).
     * @param player the sending player.
     * @param message the raw UTF-8 JSON.
     */
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != CHANNEL) return
        val msg = runCatching { json.parseToJsonElement(String(message, Charsets.UTF_8)).jsonObject }.getOrNull() ?: return
        val type = msg.str("type")
        if (type != "call") logger.info("helix:admin '$type' from ${player.name} (panel permission: ${player.hasPermission(StaffOps.MENU)})")
        when (type) {
            "hello" -> send(player, buildJsonObject { put("type", "welcome"); put("enabled", player.hasPermission(StaffOps.MENU)) })
            "open" -> if (player.hasPermission(StaffOps.MENU)) openPanel(player)
                else send(player, buildJsonObject { put("type", "denied"); put("reason", "permission") })
            "call" -> {
                val id = msg["id"]?.jsonPrimitive?.longOrNull ?: return
                val op = msg.str("op").orEmpty()
                val args = (msg["args"] as? JsonObject) ?: JsonObject(emptyMap())
                call(player, id, op, args)
            }
        }
    }

    private fun send(player: Player, message: JsonObject) {
        if (player.isOnline) player.sendPluginMessage(this, CHANNEL, message.toString().toByteArray(Charsets.UTF_8))
    }

    private fun reply(player: Player, id: Long, data: JsonObject? = null, error: String? = null) =
        send(player, buildJsonObject {
            put("type", "result")
            put("id", id)
            put("ok", error == null)
            if (error != null) put("error", error) else put("data", data ?: JsonObject(emptyMap()))
        })

    private fun openPanel(player: Player) {
        val name = player.name
        val host = player.virtualHost?.hostString
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val ticket = requestTicket(name)
            val base = PanelUrl.resolve(panelUrl, host, controlPort())
            server.scheduler.runTask(this, Runnable {
                if (ticket == null) {
                    logger.warning("panel for $name: no ticket from the node, answering 'unreachable'")
                    send(player, buildJsonObject { put("type", "denied"); put("reason", "unreachable") })
                    return@Runnable
                }
                logger.info("panel for $name: ticket issued, page $base")
                send(player, buildJsonObject { put("type", "ticket"); put("url", "$base/#ticket=$ticket&ingame=1") })
            })
        })
    }

    /** Asks the node for a one-time login ticket for [player] (blocking, off the main thread). */
    private fun requestTicket(player: String): String? = runCatching {
        val request = HttpRequest.newBuilder(URI.create("$httpUrl/api/v1/internal/panel-ticket"))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildJsonObject { put("player", player) }.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return@runCatching null
        json.parseToJsonElement(response.body()).jsonObject.str("ticket")
    }.onFailure { logger.warning("panel ticket failed: ${it.message}") }.getOrNull()

    /** Port of the node's control API (serves the dashboard); 8080 if the URL names none. */
    private fun controlPort(): Int = runCatching { URI.create(httpUrl).port }.getOrDefault(-1).takeIf { it > 0 } ?: 8080

    // ------------------------------------------------------------------ ops

    private fun call(actor: Player, id: Long, op: String, args: JsonObject) {
        val node = StaffOps.permissionFor(op) ?: return reply(actor, id, error = "unknown op $op")
        if (!actor.hasPermission(StaffOps.MENU) || !actor.hasPermission(node)) return reply(actor, id, error = "missing permission $node")
        runCatching { execute(actor, id, op, args) }.onFailure { reply(actor, id, error = it.message ?: "failed") }
    }

    private fun execute(actor: Player, id: Long, op: String, args: JsonObject) {
        val done: (JsonObject?) -> Unit = { reply(actor, id, it) }
        when (op) {
            "self.noclip" -> done(state("noclip", toggleSpectator(actor)))
            "self.heal" -> { heal(actor); done(null) }
            "self.feed" -> { feed(actor); done(null) }
            "self.fly" -> {
                actor.allowFlight = !actor.allowFlight
                if (!actor.allowFlight) actor.isFlying = false
                done(state("fly", actor.allowFlight))
            }
            "self.vanish" -> done(state("vanish", toggleVanish(actor)))
            "self.gamemode" -> { actor.gameMode = gameMode(args); done(state("gamemode", actor.gameMode.name)) }
            "self.tp" -> {
                val world = args.str("world")?.let(Bukkit::getWorld) ?: actor.world
                actor.teleport(Location(world, args.num("x"), args.num("y"), args.num("z"), actor.location.yaw, actor.location.pitch))
                done(null)
            }
            "self.spawn" -> { actor.teleport(actor.world.spawnLocation); done(null) }
            "world.time" -> {
                val ticks = StaffOps.TIME_PRESETS[args.str("value")] ?: error("unknown time ${args.str("value")}")
                actor.world.time = ticks
                done(null)
            }
            "world.weather" -> {
                when (args.str("value")) {
                    "clear" -> { actor.world.setStorm(false); actor.world.isThundering = false }
                    "rain" -> { actor.world.setStorm(true); actor.world.isThundering = false }
                    "thunder" -> { actor.world.setStorm(true); actor.world.isThundering = true }
                    else -> error("unknown weather")
                }
                done(null)
            }
            "world.clear" -> done(buildJsonObject { put("removed", clearArea(actor, args.num("radius").toInt())) })
            "server.info" -> done(serverInfo())
            in StaffOps.NODE_ACTIONS.keys -> relay(actor, id, StaffOps.NODE_ACTIONS.getValue(op), args)
            else -> playerOp(actor, id, op, args)
        }
    }

    /** Ops on another player: local when the target is here, otherwise via the addon's job queue. */
    private fun playerOp(actor: Player, id: Long, op: String, args: JsonObject) {
        val targetName = args.str("name") ?: return reply(actor, id, error = "name missing")
        val target = Bukkit.getPlayerExact(targetName)
        if (target != null) {
            when (op) {
                "player.goto" -> actor.teleport(target)
                "player.bring" -> target.teleport(actor)
                "player.spectate" -> spectate(actor, target)
                "player.freeze" -> return reply(actor, id, state("frozen", toggleFreeze(target)))
                "player.heal" -> heal(target)
                "player.feed" -> feed(target)
                "player.gamemode" -> target.gameMode = gameMode(args)
                "player.invsee" -> actor.openInventory(target.inventory)
            }
            return reply(actor, id)
        }
        if (op == "player.invsee") return reply(actor, id, error = "$targetName is on another server – go there first")
        val actorName = actor.name
        val mode = args.str("mode")
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val service = nodeAction("admin.where", targetName)
            val result = when {
                service == null -> "$targetName is not online"
                op == "player.goto" || op == "player.spectate" -> {
                    job("@$service", "arrive", buildJsonObject { put("player", actorName); put("to", targetName); put("mode", if (op == "player.spectate") "spectate" else "goto") })
                    server.scheduler.runTask(this, Runnable { connect(actor, service) })
                    null
                }
                op == "player.bring" -> {
                    job("@$serviceId", "arrive", buildJsonObject { put("player", targetName); put("to", actorName); put("mode", "goto") })
                    job(targetName, "connect", buildJsonObject { put("player", targetName); put("service", serviceId) })
                    null
                }
                else -> {
                    job(targetName, op.removePrefix("player."), buildJsonObject { put("player", targetName); if (mode != null) put("mode", mode) })
                    null
                }
            }
            server.scheduler.runTask(this, Runnable { reply(actor, id, if (result == null) buildJsonObject { put("remote", service) } else null, result) })
        })
    }

    // ------------------------------------------------------------------ jobs & presence

    private fun pollJobs() {
        val raw = nodeAction("admin.jobs", serviceId, jobCursor.toString()) ?: return
        val batch = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val jobs = (batch["jobs"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { it.jsonObject }
        jobCursor = batch["seq"]?.jsonPrimitive?.longOrNull ?: jobCursor
        if (jobs.isNotEmpty()) server.scheduler.runTask(this, Runnable { jobs.forEach(::applyJob) })
    }

    private fun applyJob(job: JsonObject) {
        val payload = job["payload"]?.jsonObject ?: return
        val name = payload.str("player") ?: return
        val player = Bukkit.getPlayerExact(name)
        when (job.str("type")) {
            "arrive" -> {
                arrivals[name.lowercase()] = Arrival(payload.str("to"), payload.str("mode") ?: "goto", System.currentTimeMillis() + ARRIVAL_TTL_MS)
                player?.let(::arrive)
            }
            "connect" -> player?.let { connect(it, payload.str("service") ?: return) }
            "freeze" -> player?.let { toggleFreeze(it) }
            "heal" -> player?.let(::heal)
            "feed" -> player?.let(::feed)
            "gamemode" -> player?.let { it.gameMode = gameMode(payload) }
        }
    }

    private fun arrive(player: Player) {
        val arrival = arrivals.remove(player.name.lowercase())?.takeIf { it.expiresAtMs > System.currentTimeMillis() } ?: return
        val target = arrival.to?.let(Bukkit::getPlayerExact) ?: return
        if (arrival.mode == "spectate") spectate(player, target) else player.teleport(target)
    }

    private fun reportPresence() {
        val report = buildJsonObject {
            put("service", serviceId)
            put("tps", Bukkit.getTPS().firstOrNull() ?: 20.0)
            put("players", buildJsonArray {
                Bukkit.getOnlinePlayers().forEach { p ->
                    add(buildJsonObject {
                        put("name", p.name)
                        put("uuid", p.uniqueId.toString())
                        put("world", p.world.name)
                        put("x", p.location.blockX); put("y", p.location.blockY); put("z", p.location.blockZ)
                        put("health", Math.round(p.health * 10) / 10.0)
                        put("food", p.foodLevel)
                        put("gamemode", p.gameMode.name)
                        put("ping", p.ping)
                        put("frozen", p.uniqueId in frozen)
                        put("vanished", p.uniqueId in vanished)
                    })
                }
            })
        }.toString()
        server.scheduler.runTaskAsynchronously(this, Runnable { nodeAction("admin.presence", report) })
    }

    /**
     * Native panels: runs a node staff action in [actor]'s name (`admin.staff`, the node checks the permission again)
     * off the main thread; the reply carries the result lines (`{"lines": [...]}`).
     */
    private fun relay(actor: Player, id: Long, action: String, args: JsonObject) {
        val arguments = (args["args"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val name = actor.name
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val result = runCatching { api.action(ActionInvocation("admin.staff", listOf(name, action) + arguments)) }.getOrNull()
            server.scheduler.runTask(this, Runnable {
                when {
                    result == null -> reply(actor, id, error = "node unreachable")
                    !result.success -> reply(actor, id, error = result.lines.firstOrNull() ?: "failed")
                    else -> reply(actor, id, buildJsonObject {
                        put("lines", kotlinx.serialization.json.JsonArray(result.lines.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    })
                }
            })
        })
    }

    private fun nodeAction(action: String, vararg arguments: String): String? {
        val result = runCatching { api.action(ActionInvocation(action, arguments.toList())) }.getOrNull() ?: return null
        return if (result.success) result.lines.firstOrNull() else null
    }

    private fun job(target: String, type: String, payload: JsonObject) {
        nodeAction("admin.job", target, type, payload.toString())
    }

    // ------------------------------------------------------------------ helpers

    private fun toggleSpectator(player: Player): Boolean {
        val previous = previousMode.remove(player.uniqueId)
        if (previous != null) {
            player.gameMode = previous
            return false
        }
        previousMode[player.uniqueId] = player.gameMode
        player.gameMode = GameMode.SPECTATOR
        return true
    }

    private fun spectate(actor: Player, target: Player) {
        if (actor.gameMode != GameMode.SPECTATOR) {
            previousMode.putIfAbsent(actor.uniqueId, actor.gameMode)
            actor.gameMode = GameMode.SPECTATOR
        }
        actor.teleport(target)
        actor.spectatorTarget = target
    }

    private fun toggleFreeze(player: Player): Boolean =
        if (!frozen.remove(player.uniqueId)) { frozen += player.uniqueId; true } else false

    private fun toggleVanish(player: Player): Boolean {
        val nowVanished = vanished.add(player.uniqueId)
        if (!nowVanished) vanished.remove(player.uniqueId)
        Bukkit.getOnlinePlayers().filter { it != player && !it.hasPermission(StaffOps.MENU) }.forEach {
            if (nowVanished) it.hidePlayer(this, player) else it.showPlayer(this, player)
        }
        return nowVanished
    }

    private fun heal(player: Player) {
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.fireTicks = 0
        feed(player)
    }

    private fun feed(player: Player) {
        player.foodLevel = 20
        player.saturation = 20f
    }

    private fun gameMode(args: JsonObject): GameMode =
        runCatching { GameMode.valueOf(args.str("mode").orEmpty().uppercase()) }.getOrElse { error("unknown game mode ${args.str("mode")}") }

    private fun clearArea(actor: Player, radius: Int): Int {
        val r = radius.coerceIn(1, StaffOps.MAX_CLEAR_RADIUS).toDouble()
        val doomed = actor.getNearbyEntities(r, r, r).filter { e ->
            e.customName() == null && e !is Tameable &&
                (e is Monster || e is Item || e is AbstractArrow || e is ExperienceOrb)
        }
        doomed.forEach { it.remove() }
        return doomed.size
    }

    private fun serverInfo(): JsonObject = buildJsonObject {
        put("service", serviceId)
        put("tps", Math.round((Bukkit.getTPS().firstOrNull() ?: 20.0) * 10) / 10.0)
        put("players", Bukkit.getOnlinePlayers().size)
        put("maxPlayers", Bukkit.getMaxPlayers())
        val rt = Runtime.getRuntime()
        put("memoryUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1_048_576)
        put("memoryMaxMb", rt.maxMemory() / 1_048_576)
        put("worlds", buildJsonArray { Bukkit.getWorlds().forEach { add(JsonPrimitive(it.name)) } })
        put("version", Bukkit.getMinecraftVersion())
    }

    private fun state(key: String, value: Any): JsonObject = buildJsonObject {
        when (value) {
            is Boolean -> put(key, value)
            else -> put(key, value.toString())
        }
    }

    private fun connect(player: Player, service: String) {
        val payload = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { out ->
                out.writeUTF("Connect")
                out.writeUTF(service)
            }
        }.toByteArray()
        player.sendPluginMessage(this, BUNGEE_CHANNEL, payload)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Double = str(key)?.toDoubleOrNull() ?: error("$key missing")

    // ------------------------------------------------------------------ events

    /**
     * Keeps frozen players in place (looking around stays possible).
     *
     * @param event the move event.
     */
    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (event.player.uniqueId !in frozen) return
        val to = event.to
        if (to.x != event.from.x || to.y != event.from.y || to.z != event.from.z) {
            event.to = event.from.clone().apply { yaw = to.yaw; pitch = to.pitch }
        }
    }

    /**
     * Hides vanished staff from a joining non-staff player and completes a pending cross-server arrival.
     *
     * @param event the join event.
     */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!player.hasPermission(StaffOps.MENU)) vanished.mapNotNull(Bukkit::getPlayer).forEach { player.hidePlayer(this, it) }
        if (arrivals.containsKey(player.name.lowercase())) server.scheduler.runTaskLater(this, Runnable { arrive(player) }, 10L)
    }

    /**
     * Restores a leaving player's game mode (noclip/spectate) and forgets their staff state.
     *
     * @param event the quit event.
     */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        previousMode.remove(id)?.let { event.player.gameMode = it }
        vanished.remove(id)
        frozen.remove(id)
    }

    private companion object {
        const val CHANNEL = "helix:admin"
        const val BUNGEE_CHANNEL = "BungeeCord"
        const val ARRIVAL_TTL_MS = 30_000L
    }
}
