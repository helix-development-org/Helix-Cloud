package org.helix.bridge.paper

import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import org.helix.api.bridge.HeartbeatReport
import org.helix.api.bridge.ResourceProbe
import org.helix.api.display.DisplayBulkRequest
import org.helix.api.display.DisplayProfile
import org.helix.api.message.LegacyToMini
import org.helix.api.proxy.JoinRequest

/**
 * Paper-side bridge between a backend server and the Helix-Cloud node.
 *
 * Reports heartbeats (players, slots, TPS), polls addon-published bridge
 * values (tab list header/footer, chat format) and renders chat with
 * addon-resolved display profiles. The first heartbeat moves the service
 * to `RUNNING` on the node.
 */
class HelixPaperBridgePlugin : JavaPlugin(), Listener {
    private val json = Json { ignoreUnknownKeys = true }
    private val scoreboardMapSerializer = MapSerializer(String.serializer(), ScoreboardData.serializer())
    private val miniMessage = MiniMessage.miniMessage()
    private val displayProfiles = ConcurrentHashMap<String, DisplayProfile>()

    /** Player uuid to assumed name; read by the packet listener on the netty threads. */
    private val nickNames = ConcurrentHashMap<java.util.UUID, String>()

    /** The registered PLAYER_INFO rewriter, or null when packetevents is absent. */
    @Volatile
    private var nickPacketListener: NickPacketListener? = null

    /** Ensures the "nick without packetevents" warning is logged only once. */
    @Volatile
    private var warnedMissingPacketRewrite = false

    @Volatile
    private var bridgeValues: Map<String, String> = emptyMap()

    @Volatile
    private var tablist: TablistData? = null

    @Volatile
    private var scoreboards: Map<String, ScoreboardData> = emptyMap()

    /** One private Bukkit scoreboard per online player, keyed by name. */
    private val playerBoards = ConcurrentHashMap<String, Scoreboard>()

    @Volatile
    private var lastFrameIndex: Int = -1
    private var heartbeatTask: BukkitTask? = null
    private var animationTask: BukkitTask? = null
    private var scoreboardTask: BukkitTask? = null
    private var scoreboardInterval: Long = -1
    private var client: NodeHttpClient? = null
    private var settings: BridgeSettings? = null
    private var pollCounter = 0

    /** Paces retries and rate-limits the "unreachable" log while the node is down. */
    private val reachability = NodeReachability()

    /**
     * Starts the sync scheduler when running under a Helix wrapper.
     */
    override fun onEnable() {
        val loaded = BridgeSettings.fromEnvironment()
        if (loaded == null) {
            logger.warning("No Helix environment found — bridge disabled.")
            return
        }
        settings = loaded
        val httpClient = NodeHttpClient(loaded)
        client = httpClient
        server.pluginManager.registerEvents(this, this)
        heartbeatTask = server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable { pulse(loaded, httpClient) },
            INITIAL_DELAY_TICKS,
            PERIOD_TICKS,
        )
        animationTask = server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable { animateTablist() },
            ANIMATION_PERIOD_TICKS,
            ANIMATION_PERIOD_TICKS,
        )
        registerNickPacketListener()
        logger.info("Helix bridge enabled for ${loaded.serviceId} → ${loaded.controlUrl}")
    }

    /**
     * Registers the PLAYER_INFO rewriter when the packetevents plugin is
     * installed; without it nicks show in chat and tab list only.
     */
    private fun registerNickPacketListener() {
        runCatching {
            val listener = NickPacketListener(nickNames)
            com.github.retrooper.packetevents.PacketEvents.getAPI().eventManager.registerListener(listener)
            nickPacketListener = listener
            logger.info("packetevents found — nicks rewrite the name tag above players")
        }.onFailure {
            logger.info("packetevents not installed — nicks show in chat/tab only, name tags keep the real name")
        }
    }

    /**
     * Cancels the sync scheduler and undoes every change made to shared
     * server state: leftover display teams and pre-login-fetched player
     * names/list-names would otherwise persist (`scoreboard.dat` on
     * non-templated servers) or go stale across a plugin reload, and a
     * second [NickPacketListener] registration on reload would rewrite
     * packets twice.
     */
    override fun onDisable() {
        heartbeatTask?.cancel()
        heartbeatTask = null
        animationTask?.cancel()
        animationTask = null
        scoreboardTask?.cancel()
        scoreboardTask = null
        clearAllScoreboards()
        server.scoreboardManager?.mainScoreboard?.teams
            ?.filter { it.name.startsWith(DISPLAY_TEAM_PREFIX) }
            ?.forEach { it.unregister() }
        server.onlinePlayers.forEach { player ->
            player.playerListName(null)
            player.displayName(null)
        }
        nickPacketListener?.let { listener ->
            runCatching {
                com.github.retrooper.packetevents.PacketEvents.getAPI().eventManager.unregisterListener(listener)
            }
        }
        nickPacketListener = null
        nickNames.clear()
    }

    /**
     * Pre-warms the nick mapping before the join broadcasts go out, so the
     * very first PLAYER_INFO other players receive already carries the
     * assumed name (no real-name flash). Uses the async pre-login event —
     * listening to PlayerLoginEvent would disable Paper's reconfiguration
     * API for the whole server.
     *
     * @param event the async pre-login event.
     */
    @EventHandler
    fun onPreLogin(event: org.bukkit.event.player.AsyncPlayerPreLoginEvent) {
        val nick = bridgeValues["nick.name.${event.name.lowercase()}"].orEmpty()
        if (nick.isNotEmpty()) {
            nickNames[event.uniqueId] = nick
        }
    }

    /**
     * Fetches the display profile of a joining player.
     *
     * @param event the join event.
     */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val httpClient = client ?: return
        server.scheduler.runTaskAsynchronously(
            this,
            Runnable { refreshDisplay(httpClient, event.player.name) },
        )
    }

    /**
     * Drops the cached display state of a quitting player and unregisters
     * their scoreboard display team everywhere it was registered — without
     * this the team (and its entry) leaks forever, persisted to
     * `scoreboard.dat` on non-templated servers.
     *
     * @param event the quit event.
     */
    @EventHandler
    fun onQuit(event: org.bukkit.event.player.PlayerQuitEvent) {
        displayProfiles.remove(event.player.name.lowercase())
        nickNames.remove(event.player.uniqueId)
        removeDisplayTeam(event.player.name)
    }

    /**
     * Unregisters a player's display team from the main scoreboard and
     * every cached private board.
     *
     * @param playerName the player whose team is removed.
     */
    private fun removeDisplayTeam(playerName: String) {
        val main = server.scoreboardManager?.mainScoreboard
        val teamName = displayTeamName(playerName)
        (listOfNotNull(main) + playerBoards.values).forEach { board -> board.getTeam(teamName)?.unregister() }
    }

    /**
     * Renders chat with the addon-published format and display profiles.
     *
     * Enforces the moderation addon's mute list and chat blocklist first
     * (there is no per-message round trip to the node, so both are checked
     * against the bridge-value snapshot synced every [PERIOD_TICKS]):
     * a muted sender or a message containing a blocked word never reaches
     * public chat OR a team/clan channel.
     *
     * Messages starting with a channel prefix (`@team`, `@clan`) never reach
     * public chat: the event is cancelled and the text is forwarded to the
     * matching node player-command (`tc` / `cc`), which delivers it to the
     * channel members network-wide and enforces the channel's permission.
     *
     * Without a published `chat.format` bridge value the vanilla chat
     * stays untouched.
     *
     * @param event the chat event.
     */
    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val plain = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (blockMuted(event) || blockFiltered(event, plain)) {
            return
        }
        val channel = CHAT_CHANNELS.entries.firstOrNull { (prefix, _) ->
            plain.length >= prefix.length && plain.substring(0, prefix.length).equals(prefix, ignoreCase = true) &&
                (plain.length == prefix.length || plain[prefix.length] == ' ')
        }
        if (channel != null) {
            event.isCancelled = true
            val playerName = event.player.name
            val text = plain.drop(channel.key.length).trim()
            server.scheduler.runTaskAsynchronously(
                this,
                Runnable { forwardChannelChat(playerName, channel.value, text) },
            )
            return
        }
        val format = bridgeValues["chat.format"] ?: return
        val profile = displayProfiles[event.player.name.lowercase()] ?: DisplayProfile()
        event.renderer(
            ChatRenderer { source, _, message, _ ->
                val plainMessage = PlainTextComponentSerializer.plainText().serialize(message)
                colored(
                    format
                        .replace("{prefix}", profile.prefix)
                        .replace("{suffix}", profile.suffix)
                        .replace("{color}", profile.color)
                        .replace("{name}", profile.nameOr(source.name))
                        // players must not inject MiniMessage tags (e.g. click events)
                        .replace("{message}", miniMessage.escapeTags(plainMessage)),
                )
            },
        )
    }

    /**
     * Cancels the event and notifies the sender when [ChatModerationGate]
     * reports an active network mute (`moderation.mutes` bridge value).
     *
     * @param event the chat event.
     * @return `true` when the message was blocked.
     */
    private fun blockMuted(event: AsyncChatEvent): Boolean {
        val mutes = decodeBridgeMap<Long>("moderation.mutes") ?: return false
        if (!ChatModerationGate.isMuted(mutes, event.player.name, System.currentTimeMillis())) {
            return false
        }
        event.isCancelled = true
        event.player.sendMessage(colored(localizedBridgeText("moderation.muteMessage", event.player)))
        return true
    }

    /**
     * Cancels the event and notifies the sender when [ChatModerationGate]
     * reports the message contains a word from the configured blocklist
     * (`moderation.blocklist` bridge value).
     *
     * @param event the chat event.
     * @param plain the plain-text message.
     * @return `true` when the message was blocked.
     */
    private fun blockFiltered(event: AsyncChatEvent, plain: String): Boolean {
        val blocked = bridgeValues["moderation.blocklist"]
            ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: return false
        if (!ChatModerationGate.isBlocked(blocked, plain)) {
            return false
        }
        event.isCancelled = true
        event.player.sendMessage(colored(localizedBridgeText("moderation.blockedMessage", event.player)))
        return true
    }

    /**
     * Decodes a bridge value published as a JSON object with values of type
     * [V], or `null` when absent/malformed.
     */
    private inline fun <reified V> decodeBridgeMap(key: String): Map<String, V>? =
        bridgeValues[key]?.let { runCatching { json.decodeFromString<Map<String, V>>(it) }.getOrNull() }

    /**
     * Resolves a bilingual (`en`/`de`) bridge-published text by the player's
     * own reported client locale via [ChatModerationGate.localize] — there
     * is no per-message round trip to the node here, so this cannot go
     * through the node's per-player language preference like every other
     * moderation message; the client's self-reported locale is the only
     * language signal available bridge-side.
     */
    private fun localizedBridgeText(key: String, player: Player): String {
        val texts = decodeBridgeMap<String>(key) ?: return ""
        return ChatModerationGate.localize(texts, player.locale().language)
    }

    /**
     * Forwards a channel-chat message (`@team`/`@clan`) to the node's
     * player-command endpoint and relays any feedback lines (usage, missing
     * permission, "no clan") back to the sender. Runs off the main thread.
     *
     * @param playerName the sending player.
     * @param command the node player-command (`tc` or `cc`).
     * @param text the message text, may be empty (the action answers with
     *   its usage line).
     */
    private fun forwardChannelChat(playerName: String, command: String, text: String) {
        val httpClient = client ?: return
        val response = runCatching {
            httpClient.postJsonForBody(
                "/api/v1/internal/player-command",
                json.encodeToString(
                    org.helix.api.action.PlayerCommandRequest(
                        player = playerName,
                        command = command,
                        arguments = if (text.isEmpty()) emptyList() else text.split(" "),
                    ),
                ),
            )
        }.onFailure { logger.warning("Channel chat @$command failed: ${it.message}") }.getOrNull()
        val player = server.getPlayerExact(playerName) ?: return
        if (response == null) {
            player.sendMessage(colored("&cThis chat channel is currently unavailable."))
            return
        }
        val result = json.decodeFromString<org.helix.api.action.ActionResult>(response)
        result.lines.forEach { line -> player.sendMessage(colored(line)) }
        if (!result.success && result.lines.isEmpty()) {
            player.sendMessage(colored("&cThis chat channel is not available to you."))
        }
    }

    private fun pulse(settings: BridgeSettings, client: NodeHttpClient) {
        if (reachability.shouldAttempt()) {
            val heartbeatOk = sendHeartbeat(settings, client)
            val valuesOk = syncBridgeValues(settings, client)
            if (heartbeatOk && valuesOk) {
                if (reachability.isDown()) {
                    logger.info("Helix node reachable again for ${settings.serviceId}")
                }
                reachability.recordSuccess()
            } else {
                val downSince = reachability.recordFailure()
                logger.warning(
                    "Helix node unreachable since ${java.time.Instant.ofEpochMilli(downSince)} — " +
                        "using cached values, retrying with backoff",
                )
            }
        }
        applyTablist()
        ensureScoreboardTask()
        // The display cycle needs a live node round-trip per refresh, so it is skipped entirely
        // while down — the last known-good cached profiles keep rendering instead of being cleared.
        if (reachability.isDown()) {
            return
        }
        if (pollCounter++ % DISPLAY_REFRESH_CYCLES == 0) {
            refreshAllDisplays(client, server.onlinePlayers.map { it.name })
        } else {
            // Nick changes must not wait for the slow display cycle: the nick addon publishes
            // nick.name.<player> bridge values, so a mismatch against the cached profile
            // triggers an immediate refresh (one poll = 5s worst case).
            server.onlinePlayers.forEach { player ->
                val expected = bridgeValues["nick.name.${player.name.lowercase()}"].orEmpty()
                val current = displayProfiles[player.name.lowercase()]?.name.orEmpty()
                if (expected != current) {
                    refreshDisplay(client, player.name)
                }
            }
        }
    }

    private fun sendHeartbeat(settings: BridgeSettings, client: NodeHttpClient): Boolean {
        val report = HeartbeatReport(
            serviceId = settings.serviceId,
            onlinePlayers = server.onlinePlayers.size,
            maxPlayers = server.maxPlayers,
            tps = server.tps.firstOrNull(),
            memoryUsedMb = ResourceProbe.memoryUsedMb(),
            memoryMaxMb = ResourceProbe.memoryMaxMb(),
            cpuPercent = ResourceProbe.cpuPercent(),
        )
        return runCatching { client.postJson("/api/v1/internal/heartbeat", json.encodeToString(report)) }
            .getOrDefault(false)
    }

    private fun syncBridgeValues(settings: BridgeSettings, client: NodeHttpClient): Boolean =
        runCatching {
            val body = client.getJson("/api/v1/internal/bridge-values?serviceId=${settings.serviceId}")
                ?: return@runCatching false
            bridgeValues = json.decodeFromString<Map<String, String>>(body)
            tablist = bridgeValues["tablist.config"]?.let { raw ->
                runCatching { json.decodeFromString<TablistData>(raw) }.getOrNull()
            }
            scoreboards = bridgeValues["scoreboard.config"]?.let { raw ->
                runCatching { json.decodeFromString(scoreboardMapSerializer, raw) }.getOrNull()
            } ?: emptyMap()
            true
        }.getOrDefault(false)

    private fun applyTablist() {
        val config = tablist
        val header: String
        val footer: String
        if (config != null) {
            val index = config.frameIndexAt(System.currentTimeMillis())
            lastFrameIndex = index
            header = config.headerAt(index)
            footer = config.footerAt(index)
        } else {
            // Fallback for older tablist addons publishing only the two keys.
            header = bridgeValues["tablist.header"] ?: ""
            footer = bridgeValues["tablist.footer"] ?: ""
            if (header.isEmpty() && footer.isEmpty()) {
                return
            }
        }
        server.onlinePlayers.forEach { player ->
            player.sendPlayerListHeaderAndFooter(
                colored(placeholders(header)),
                colored(placeholders(footer)),
            )
        }
    }

    /**
     * Advances the tab list animation: re-applies header/footer whenever the
     * time-based frame index changed. Static tab lists (one frame) are left
     * to the regular pulse.
     */
    private fun animateTablist() {
        val config = tablist ?: return
        if (config.frameCount() <= 1) {
            return
        }
        if (config.frameIndexAt(System.currentTimeMillis()) != lastFrameIndex) {
            applyTablist()
        }
    }

    /**
     * The board configured for this server's task, or the shared `default`
     * board. `null` when no scoreboard is published at all.
     */
    private fun activeBoard(): ScoreboardData? {
        val map = scoreboards
        if (map.isEmpty()) return null
        return map[settings?.task] ?: map["default"]
    }

    /**
     * Schedules, reschedules or tears down the sidebar refresh task so its
     * period matches the active board's interval. Safe to call from the
     * async pulse; the actual scoreboard mutation runs on the main thread.
     */
    private fun ensureScoreboardTask() {
        val board = activeBoard()?.takeIf { it.enabled && it.lines.isNotEmpty() }
        if (board == null) {
            if (scoreboardTask != null || playerBoards.isNotEmpty()) {
                scoreboardTask?.cancel()
                scoreboardTask = null
                scoreboardInterval = -1
                server.scheduler.runTask(this, Runnable { clearAllScoreboards() })
            }
            return
        }
        val interval = board.intervalTicks()
        if (scoreboardTask != null && scoreboardInterval == interval) {
            return
        }
        scoreboardTask?.cancel()
        scoreboardInterval = interval
        scoreboardTask = server.scheduler.runTaskTimer(this, Runnable { refreshScoreboards() }, 1L, interval)
    }

    private fun refreshScoreboards() {
        val board = activeBoard()?.takeIf { it.enabled && it.lines.isNotEmpty() }
        if (board == null) {
            clearAllScoreboards()
            return
        }
        val manager = server.scoreboardManager ?: return
        server.onlinePlayers.forEach { player -> runCatching { applyBoard(player, board, manager) } }
        playerBoards.keys.toList().forEach { name ->
            if (server.getPlayerExact(name) == null) {
                playerBoards.remove(name)
            }
        }
    }

    /**
     * Builds (or updates in place, to avoid flicker) the player's private
     * sidebar scoreboard from [board], substituting placeholders per player.
     * Each line is stored on a per-index team prefix with a unique invisible
     * score entry, so identical lines still render (a Bukkit quirk).
     *
     * @param player the viewer.
     * @param board the active board configuration.
     * @param manager the server scoreboard manager.
     */
    private fun applyBoard(
        player: Player,
        board: ScoreboardData,
        manager: org.bukkit.scoreboard.ScoreboardManager,
    ) {
        // A fresh private board must carry the display teams of everyone online, otherwise this
        // viewer would see plain name tags (teams render from the viewer's scoreboard).
        val scoreboard = playerBoards.getOrPut(player.name) { manager.newScoreboard.also(::seedDisplayTeams) }
        val objective = scoreboard.getObjective(OBJECTIVE_NAME)
            ?: scoreboard.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, Component.empty()).also {
                it.displaySlot = DisplaySlot.SIDEBAR
                runCatching { it.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank()) }
            }
        objective.displayName(colored(placeholders(board.title, player)))
        val lines = board.boundedLines()
        lines.forEachIndexed { index, raw ->
            val entry = invisibleEntry(index)
            val team = scoreboard.getTeam(LINE_TEAM_PREFIX + index)
                ?: scoreboard.registerNewTeam(LINE_TEAM_PREFIX + index)
            team.entries.filter { it != entry }.forEach { team.removeEntry(it) }
            if (!team.hasEntry(entry)) {
                team.addEntry(entry)
            }
            team.prefix(colored(placeholders(raw, player)))
            objective.getScore(entry).score = lines.size - index
        }
        for (index in lines.size until ScoreboardData.MAX_LINES) {
            scoreboard.getTeam(LINE_TEAM_PREFIX + index)?.unregister()
            scoreboard.resetScores(invisibleEntry(index))
        }
        if (player.scoreboard !== scoreboard) {
            player.scoreboard = scoreboard
        }
    }

    private fun clearAllScoreboards() {
        val main = server.scoreboardManager?.mainScoreboard
        playerBoards.keys.toList().forEach { name ->
            playerBoards.remove(name)
            if (main != null) {
                server.getPlayerExact(name)?.scoreboard = main
            }
        }
    }

    /**
     * A unique, visually empty score entry for a line index — two `§` color
     * codes render nothing yet keep every line's entry distinct, which lets
     * duplicate line texts (held in the team prefix) coexist on the sidebar.
     */
    private fun invisibleEntry(index: Int): String {
        val hex = "0123456789abcdef"
        return "§${hex[index % 16]}§r"
    }

    private fun placeholders(text: String, player: Player): String {
        val location = player.location
        val now = LocalTime.now()
        val profile = displayProfiles[player.name.lowercase()] ?: DisplayProfile()
        return text
            .replace("{player}", player.name)
            .replace("{displayname}", profile.displayName(player.name))
            .replace("{nick}", profile.nameOr(player.name))
            .replace("{online}", (bridgeValues["network.online"]?.toIntOrNull() ?: server.onlinePlayers.size).toString())
            .replace("{max}", server.maxPlayers.toString())
            .replace("{server}", settings?.serviceId ?: "")
            .replace("{task}", settings?.task ?: "")
            .replace("{ping}", player.ping.toString())
            .replace("{tps}", String.format(java.util.Locale.ROOT, "%.1f", server.tps.firstOrNull() ?: 20.0))
            .replace("{world}", location.world?.name ?: "")
            .replace("{x}", location.blockX.toString())
            .replace("{y}", location.blockY.toString())
            .replace("{z}", location.blockZ.toString())
            .replace("{date}", LocalDate.now().toString())
            .replace("{time}", String.format(java.util.Locale.ROOT, "%02d:%02d", now.hour, now.minute))
            .replace("{network}", bridgeValues["network.name"] ?: "")
            .replace("{prefix}", bridgeValues["network.prefix"] ?: "")
            .replace("{balance}", bridgeValues["economy.balance.${player.name.lowercase()}"] ?: "")
            .replace("{clan}", bridgeValues["clan.tag.${player.name.lowercase()}"] ?: "")
    }

    private fun placeholders(text: String): String = text
        .replace("{online}", server.onlinePlayers.size.toString())
        .replace("{max}", server.maxPlayers.toString())
        .replace("{prefix}", bridgeValues["network.prefix"] ?: "")
        .replace("{network}", bridgeValues["network.name"] ?: "")

    private fun refreshDisplay(client: NodeHttpClient, playerName: String) {
        runCatching {
            client.postJsonForBody(
                "/api/v1/internal/display",
                json.encodeToString(JoinRequest(name = playerName)),
            )?.let { body ->
                val profile = json.decodeFromString<DisplayProfile>(body)
                displayProfiles[playerName.lowercase()] = profile
                server.getPlayerExact(playerName)?.let { player ->
                    server.scheduler.runTask(this, Runnable { applyDisplay(player, profile) })
                }
            }
        }.onFailure { logger.warning("Helix display fetch failed: ${it.message}") }
    }

    /**
     * Refreshes every online player's display profile with a single bulk
     * call instead of one `POST /internal/display` per player, and only
     * re-applies scoreboard teams/packets for a player whose resolved
     * profile actually changed since the last cycle (avoids redundant
     * packet spam on the default interval).
     *
     * @param client the node HTTP client.
     * @param names online player names to refresh.
     */
    private fun refreshAllDisplays(client: NodeHttpClient, names: List<String>) {
        if (names.isEmpty()) return
        runCatching {
            client.postJsonForBody(
                "/api/v1/internal/display-bulk",
                json.encodeToString(DisplayBulkRequest(names)),
            )?.let { body ->
                val profiles = json.decodeFromString<Map<String, DisplayProfile>>(body)
                profiles.forEach { (name, profile) ->
                    val key = name.lowercase()
                    val changed = displayProfiles[key] != profile
                    displayProfiles[key] = profile
                    if (changed) {
                        server.getPlayerExact(name)?.let { player ->
                            server.scheduler.runTask(this, Runnable { applyDisplay(player, profile) })
                        }
                    }
                }
            }
        }.onFailure { logger.warning("Helix bulk display fetch failed: ${it.message}") }
    }

    /**
     * Applies a display profile to the player's tab-list entry, display name
     * and the name shown above their head. Name tags render from the VIEWER's
     * scoreboard, and the sidebar gives every player a private board — so the
     * display team must exist on the main board AND every private board, not
     * just on one of them (that was why name tags stayed plain). Must run on
     * the main server thread.
     *
     * @param player the online player.
     * @param profile the resolved prefix/nick/suffix/color.
     */
    private fun applyDisplay(player: Player, profile: DisplayProfile) {
        val main = server.scoreboardManager?.mainScoreboard ?: return
        val boards = listOf(main) + playerBoards.values
        val previousNick = nickNames[player.uniqueId].orEmpty()
        if (profile.name.isEmpty()) nickNames.remove(player.uniqueId) else nickNames[player.uniqueId] = profile.name
        if (profile.isPlain()) {
            val teamName = displayTeamName(player.name)
            boards.forEach { board ->
                board.getTeam(teamName)?.let { team -> team.entries.toList().forEach(team::removeEntry) }
            }
            player.playerListName(null)
            player.displayName(null)
        } else {
            boards.forEach { board -> applyDisplayTeam(board, player.name, profile) }
            val composed = colored(profile.displayName(player.name))
            player.playerListName(composed)
            player.displayName(composed)
        }
        if (previousNick != profile.name) {
            logger.info("Nick display for ${player.name}: '$previousNick' → '${profile.name}'")
            rescopePlayer(player)
        }
    }

    /**
     * Re-sends PLAYER_INFO and spawn packets of [player] to every viewer via
     * Bukkit's hide/show cycle. The packet listener rewrites the fresh
     * packets in flight, so a changed (or removed) nick reaches clients that
     * already knew the player. Hide and show are spread over two ticks so
     * the client processes a clean REMOVE → ADD → SPAWN sequence. Must run
     * on the main server thread.
     */
    private fun rescopePlayer(player: Player) {
        val listener = nickPacketListener
        if (listener == null) {
            if (!warnedMissingPacketRewrite) {
                warnedMissingPacketRewrite = true
                logger.warning(
                    "A nick is active but packetevents is not installed on this server — " +
                        "name tags keep the real name (nick shows in chat/tab only). " +
                        "packetevents ships with the Helix-Guard addon (paper/packetevents.jar).",
                )
            }
            return
        }
        val viewers = server.onlinePlayers.filter { it !== player }
        viewers.forEach { viewer -> viewer.hidePlayer(this, player) }
        server.scheduler.runTaskLater(
            this,
            Runnable {
                val target = server.getPlayerExact(player.name) ?: return@Runnable
                viewers.forEach { viewer -> if (viewer.isOnline) viewer.showPlayer(this, target) }
                logger.info(
                    if (viewers.isEmpty()) {
                        "Nick display for ${player.name}: no other players online — the nick name tag " +
                            "only affects what OTHER players see (the own tag, e.g. via LabyMod, keeps the real name)"
                    } else {
                        "Nick display for ${player.name} re-sent to ${viewers.size} viewers " +
                            "(profile rewrites so far: ${listener.rewrites.get()})"
                    },
                )
            },
            2L,
        )
    }

    /**
     * Creates or updates the player's display team (name-tag prefix/suffix/
     * color) on one scoreboard. The team entry is the DISPLAYED name — for
     * a nicked player the client only knows the entity under its assumed
     * name, so the entry must match that for the tag to attach.
     *
     * @param board the scoreboard to hold the team.
     * @param playerName the player's account name.
     * @param profile the resolved display profile.
     */
    private fun applyDisplayTeam(board: Scoreboard, playerName: String, profile: DisplayProfile) {
        val teamName = displayTeamName(playerName)
        val entry = profile.nameOr(playerName)
        val team = board.getTeam(teamName) ?: board.registerNewTeam(teamName)
        team.entries.filter { it != entry }.forEach(team::removeEntry)
        team.prefix(colored(profile.prefix))
        team.suffix(colored(profile.suffix))
        namedColor(profile.color)?.let { team.color(it) }
        if (!team.hasEntry(entry)) {
            team.addEntry(entry)
        }
    }

    /** Seeds all known display teams onto a freshly created private board. */
    private fun seedDisplayTeams(board: Scoreboard) {
        displayProfiles.forEach { (lowerName, profile) ->
            if (!profile.isPlain()) {
                server.getPlayerExact(lowerName)?.let { applyDisplayTeam(board, it.name, profile) }
            }
        }
    }

    /** Stable scoreboard-safe team name for a player (16 char limit). */
    private fun displayTeamName(playerName: String) =
        DISPLAY_TEAM_PREFIX + Integer.toHexString(playerName.lowercase().hashCode())

    /**
     * Maps a legacy `&`-color code to a named colour for scoreboard teams.
     *
     * @param code a color string such as `&c`, or empty.
     * @return the matching colour, or `null` when none applies.
     */
    private fun namedColor(code: String): NamedTextColor? {
        val ch = code.trim().removePrefix("&").removePrefix("§").firstOrNull()?.lowercaseChar() ?: return null
        return when (ch) {
            '0' -> NamedTextColor.BLACK
            '1' -> NamedTextColor.DARK_BLUE
            '2' -> NamedTextColor.DARK_GREEN
            '3' -> NamedTextColor.DARK_AQUA
            '4' -> NamedTextColor.DARK_RED
            '5' -> NamedTextColor.DARK_PURPLE
            '6' -> NamedTextColor.GOLD
            '7' -> NamedTextColor.GRAY
            '8' -> NamedTextColor.DARK_GRAY
            '9' -> NamedTextColor.BLUE
            'a' -> NamedTextColor.GREEN
            'b' -> NamedTextColor.AQUA
            'c' -> NamedTextColor.RED
            'd' -> NamedTextColor.LIGHT_PURPLE
            'e' -> NamedTextColor.YELLOW
            'f' -> NamedTextColor.WHITE
            else -> null
        }
    }

    private fun colored(text: String): Component =
        miniMessage.deserialize(LegacyToMini.translate(text))

    private companion object {
        /** Ticks before the first sync (1 second). */
        const val INITIAL_DELAY_TICKS = 20L

        /** Ticks between syncs (5 seconds). */
        const val PERIOD_TICKS = 100L

        /** Sync cycles between full display profile refreshes (30 s). */
        const val DISPLAY_REFRESH_CYCLES = 6

        /** Ticks between tab list animation checks (250 ms). */
        const val ANIMATION_PERIOD_TICKS = 5L

        /** Sidebar objective name (must stay within 16 characters). */
        const val OBJECTIVE_NAME = "helix_sidebar"

        /** Team name prefix holding each sidebar line's text. */
        const val LINE_TEAM_PREFIX = "hline"

        /** Team name prefix of a player's display (name-tag prefix/suffix/color) team. */
        const val DISPLAY_TEAM_PREFIX = "hlx"

        /** Chat prefixes routed to node player-commands instead of public chat. */
        val CHAT_CHANNELS = mapOf("@team" to "tc", "@clan" to "cc")
    }
}
