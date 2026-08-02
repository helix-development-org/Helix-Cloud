package org.helix.addons.labymod.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.labymod.serverapi.api.model.component.ServerAPIComponent
import net.labymod.serverapi.core.model.feature.DiscordRPC
import net.labymod.serverapi.core.model.feature.Emote
import net.labymod.serverapi.core.model.feature.InteractionMenuEntry
import net.labymod.serverapi.core.model.supplement.InputPrompt
import net.labymod.serverapi.core.model.supplement.ServerSwitchPrompt
import net.labymod.serverapi.core.packet.clientbound.game.display.TabListBannerPacket
import net.labymod.serverapi.core.packet.clientbound.game.feature.EmotePacket
import net.labymod.serverapi.integration.voicechat.VoiceChatPlayer
import net.labymod.serverapi.integration.voicechat.model.VoiceChatMute
import net.labymod.serverapi.server.velocity.LabyModPlayer
import net.labymod.serverapi.server.velocity.LabyModProtocolService
import net.labymod.serverapi.server.velocity.event.LabyModPlayerJoinEvent
import org.slf4j.Logger

/**
 * Velocity component of the Helix LabyMod addon — the network's single
 * LabyMod protocol endpoint.
 *
 * The official server API intercepts the `labymod:neo` payloads at the
 * proxy (they never reach the backends), so detection and every feature
 * packet run here. All feature data arrives over the node's bridge
 * values, polled once per second with the per-service token: the addon
 * config (`labymod.config`), balances (`economy.balance.*`), subtitles
 * (`subtitle.text.*`), voice mutes (`labymod.voicemutes`), NPC entities
 * (`labymod.npcs`) and the one-shot command queue (`labymod.cmd`).
 */
class LabyModVelocityPlugin @Inject constructor(
    private val server: ProxyServer,
    private val logger: Logger,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private var controlUrl: String = ""
    private var token: String = ""

    @Volatile
    private var config = LabyConfigValue()

    @Volatile
    private var values: Map<String, String> = emptyMap()

    @Volatile
    private var npcEntities: Map<String, Map<String, String>> = emptyMap()
    private val sentBalances = ConcurrentHashMap<UUID, String>()
    private val sentSubtitles = ConcurrentHashMap<UUID, String>()
    private val mutedPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val idleClocks = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastCommandSeq: Long = -1

    /**
     * Initializes the LabyMod protocol service and the poll loops.
     *
     * @param event the proxy init event.
     */
    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        controlUrl = System.getenv("HELIX_CONTROL_URL").orEmpty().trimEnd('/')
        token = System.getenv("HELIX_CONTROL_TOKEN").orEmpty()
        if (controlUrl.isBlank() || token.isBlank()) {
            logger.warn("No Helix environment found — LabyMod integration disabled.")
            return
        }
        LabyModProtocolService.initialize(this, server, logger)
        server.scheduler.buildTask(this, Runnable { poll() })
            .repeat(1, TimeUnit.SECONDS)
            .schedule()
        server.scheduler.buildTask(this, Runnable { idleEmoteTick() })
            .repeat(1, TimeUnit.SECONDS)
            .schedule()
        logger.info("Helix LabyMod integration enabled")
    }

    /**
     * Applies the on-join features and reports the detection to the node.
     *
     * @param event the LabyMod join event from the protocol service.
     */
    @Subscribe
    fun onLabyModJoin(event: LabyModPlayerJoinEvent) {
        val labyPlayer = event.labyModPlayer()
        val player = labyPlayer.player
        invokeAction(
            "labymod.report",
            listOf(player.username, player.uniqueId.toString(), labyPlayer.labyModVersion),
        )
        if (config.interactionMenu && config.menuEntries.isNotEmpty()) {
            labyPlayer.sendInteractionMenuEntries(
                config.menuEntries.map { entry ->
                    InteractionMenuEntry.create(
                        ServerAPIComponent.text(entry.label),
                        InteractionMenuEntry.InteractionMenuType.RUN_COMMAND,
                        entry.command,
                    )
                },
            )
        }
        applyEconomy(labyPlayer)
        applySubtitle(labyPlayer)
        applyRpc(labyPlayer)
        if (config.voiceMuteSync && player.uniqueId in mutedPlayers) {
            voiceChat(labyPlayer)?.mute(VoiceChatMute.create(player.uniqueId, "Muted on this network"))
        }
    }

    /**
     * Refreshes the Rich Presence after every backend switch.
     *
     * @param event the post-connect event.
     */
    @Subscribe
    fun onServerConnected(event: ServerPostConnectEvent) {
        labyPlayer(event.player.uniqueId)?.let(::applyRpc)
    }

    /**
     * Drops the per-player caches on disconnect.
     *
     * @param event the disconnect event.
     */
    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        sentBalances.remove(event.player.uniqueId)
        sentSubtitles.remove(event.player.uniqueId)
    }

    // ------------------------------------------------------------------
    // Poll loop
    // ------------------------------------------------------------------

    private fun poll() {
        val fetched = fetchBridgeValues() ?: return
        values = fetched
        fetched["labymod.config"]?.let { raw ->
            runCatching { json.decodeFromString<LabyConfigValue>(raw) }.getOrNull()?.let { config = it }
        }
        fetched["labymod.npcs"]?.let { raw ->
            runCatching { json.decodeFromString<Map<String, Map<String, String>>>(raw) }
                .getOrNull()?.let { npcEntities = it }
        }
        service().forEachPlayer { labyPlayer ->
            applyEconomy(labyPlayer)
            applySubtitle(labyPlayer)
        }
        applyVoiceMutes(fetched["labymod.voicemutes"])
        applyCommands(fetched["labymod.cmd"])
    }

    private fun applyEconomy(labyPlayer: LabyModPlayer) {
        if (!config.economyHud) {
            return
        }
        val balance = values["economy.balance.${labyPlayer.player.username.lowercase()}"] ?: return
        if (sentBalances.put(labyPlayer.uniqueId, balance) == balance) {
            return
        }
        val amount = balance.toDoubleOrNull() ?: return
        labyPlayer.updateCashEconomy { economy ->
            economy.visible(true)
            economy.balance(amount)
        }
    }

    private fun applySubtitle(labyPlayer: LabyModPlayer) {
        if (!config.subtitlesSync) {
            return
        }
        val text = stripFormatting(values["subtitle.text.${labyPlayer.player.username.lowercase()}"].orEmpty())
        if (sentSubtitles.put(labyPlayer.uniqueId, text) == text) {
            return
        }
        if (text.isBlank()) {
            labyPlayer.resetSubtitle()
        } else {
            labyPlayer.updateSubtitle(ServerAPIComponent.text(text))
        }
    }

    private fun applyRpc(labyPlayer: LabyModPlayer) {
        if (!config.discordRpc) {
            return
        }
        val serviceId = labyPlayer.player.currentServer.map { it.serverInfo.name }.orElse("")
        val text = config.rpcFormat
            .replace("{network}", values["network.name"] ?: "Helix")
            .replace("{service}", serviceId)
            .replace("{task}", serviceId.substringBeforeLast('-'))
            .trim()
        if (text.isNotBlank()) {
            labyPlayer.sendDiscordRPC(DiscordRPC.create(text))
        }
    }

    private fun applyVoiceMutes(raw: String?) {
        if (!config.voiceMuteSync || raw == null) {
            return
        }
        val mutes = runCatching { json.decodeFromString<List<MuteValue>>(raw) }.getOrNull() ?: return
        val mutedNames = mutes.map { it.player.lowercase() }.toSet()
        val current = server.allPlayers.filter { it.username.lowercase() in mutedNames }
            .map(Player::getUniqueId)
            .toSet()
        (current - mutedPlayers).forEach { uuid ->
            mutedPlayers += uuid
            labyPlayer(uuid)?.let { voiceChat(it)?.mute(VoiceChatMute.create(uuid, "Muted on this network")) }
        }
        (mutedPlayers - current).forEach { uuid ->
            mutedPlayers -= uuid
            labyPlayer(uuid)?.let { voiceChat(it)?.unmute() }
        }
    }

    private fun applyCommands(raw: String?) {
        if (raw == null) {
            return
        }
        val queue = runCatching { json.decodeFromString<LabyCommandQueueValue>(raw) }.getOrNull() ?: return
        val newest = queue.entries.maxOfOrNull { it.seq } ?: return
        if (lastCommandSeq < 0) {
            // first poll after a proxy start: fast-forward instead of replaying
            lastCommandSeq = newest
            return
        }
        queue.entries.filter { it.seq > lastCommandSeq }.sortedBy { it.seq }.forEach(::execute)
        lastCommandSeq = maxOf(lastCommandSeq, newest)
    }

    private fun execute(command: LabyCommandValue) {
        when (command.type) {
            "emote" -> {
                val uuid = command.args.getOrNull(0)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return
                val emotes = command.args.getOrNull(1).orEmpty()
                    .split(",").mapNotNull { it.trim().toIntOrNull() }
                if (config.npcEmotes && emotes.isNotEmpty()) {
                    sendEmote(command.service, uuid, emotes)
                }
            }
            "marker" -> targets(command).forEach { labyPlayer ->
                val (x, y, z) = command.args.take(3).mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }
                    ?: return@forEach
                labyPlayer.sendMarker(labyPlayer.uniqueId, x, y, z, true, null)
            }
            "input" -> targets(command).forEach { labyPlayer ->
                labyPlayer.openInputPrompt(
                    InputPrompt.builder().title(ServerAPIComponent.text(command.args.firstOrNull().orEmpty())).build(),
                ) { response ->
                    invokeAction(
                        "labymod.prompt.response",
                        listOf(labyPlayer.player.username, response),
                    )
                }
            }
            "serverswitch" -> targets(command).forEach { labyPlayer ->
                val address = command.args.getOrNull(0) ?: return@forEach
                val title = command.args.getOrNull(1).orEmpty().ifBlank { address }
                labyPlayer.openServerSwitchPrompt(ServerSwitchPrompt.create(ServerAPIComponent.text(title), address))
            }
            "banner" -> targets(command).forEach { labyPlayer ->
                command.args.firstOrNull()?.let { labyPlayer.sendPacket(TabListBannerPacket(it)) }
            }
        }
    }

    private fun idleEmoteTick() {
        if (!config.npcEmotes || npcEntities.isEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        npcEntities.forEach { (serviceId, npcs) ->
            npcs.forEach inner@{ (npcId, entityUuid) ->
                val emotes = config.npcs[npcId] ?: return@inner
                if (emotes.idleEmotes.isEmpty()) {
                    return@inner
                }
                val key = "$serviceId/$npcId"
                val last = idleClocks[key] ?: 0L
                if (now - last < emotes.idleIntervalSeconds.coerceAtLeast(5) * 1000L) {
                    return@inner
                }
                idleClocks[key] = now
                val uuid = runCatching { UUID.fromString(entityUuid) }.getOrNull() ?: return@inner
                sendEmote(serviceId, uuid, listOf(emotes.idleEmotes.random()))
            }
        }
    }

    private fun sendEmote(serviceId: String, entity: UUID, emotes: List<Int>) {
        val packet = EmotePacket(emotes.map { Emote.play(entity, it) })
        service().forEachPlayer { labyPlayer ->
            val onService = labyPlayer.player.currentServer.map { it.serverInfo.name == serviceId }.orElse(false)
            if (serviceId.isBlank() || onService) {
                labyPlayer.sendPacket(packet)
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun service(): LabyModProtocolService = LabyModProtocolService.get()

    private fun labyPlayer(uuid: UUID): LabyModPlayer? =
        runCatching { service().getPlayer(uuid) }.getOrNull()

    private fun voiceChat(labyPlayer: LabyModPlayer): VoiceChatPlayer? =
        runCatching { labyPlayer.getIntegrationPlayer(VoiceChatPlayer::class.java) }.getOrNull()

    private fun targets(command: LabyCommandValue): List<LabyModPlayer> = when {
        command.player.equals("all", ignoreCase = true) -> service().getPlayers().toList()
        command.player.isNotBlank() ->
            server.getPlayer(command.player).flatMap { player -> java.util.Optional.ofNullable(labyPlayer(player.uniqueId)) }
                .map(::listOf).orElse(emptyList())
        else -> emptyList()
    }

    private fun stripFormatting(text: String): String =
        text.replace(Regex("&[0-9a-fk-orA-FK-OR]|<[^<>]{1,32}>"), "")

    private fun fetchBridgeValues(): Map<String, String>? = runCatching {
        val request = HttpRequest.newBuilder(URI.create("$controlUrl/api/v1/internal/bridge-values"))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        json.parseToJsonElement(response.body()).jsonObject
            .mapValues { (_, value) -> value.jsonPrimitive.content }
    }.getOrNull()

    private fun invokeAction(action: String, arguments: List<String>) {
        server.scheduler.buildTask(
            this,
            Runnable {
                runCatching {
                    val body = json.encodeToString(ActionCall(action, arguments))
                    val request = HttpRequest.newBuilder(URI.create("$controlUrl/api/v1/internal/action"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()
                    http.send(request, HttpResponse.BodyHandlers.ofString())
                }.onFailure { logger.warn("LabyMod action {} failed: {}", action, it.message) }
            },
        ).schedule()
    }

    /**
     * Request body of `POST /api/v1/internal/action`.
     *
     * @property action the action name.
     * @property arguments positional arguments.
     */
    @kotlinx.serialization.Serializable
    data class ActionCall(val action: String, val arguments: List<String>)
}
