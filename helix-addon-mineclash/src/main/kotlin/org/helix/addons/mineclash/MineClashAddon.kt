package org.helix.addons.mineclash

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.helix.addon.sdk.AddonBase
import org.helix.addons.mineclash.clan.ClanLookup
import org.helix.addons.mineclash.config.ConfigDocument
import org.helix.addons.mineclash.config.ConfigStore
import org.helix.addons.mineclash.profile.DocumentProfileRepository
import org.helix.addons.mineclash.profile.MatchRecord
import org.helix.addons.mineclash.profile.PlayerProfile
import org.helix.addons.mineclash.profile.PostgresProfileRepository
import org.helix.addons.mineclash.profile.ProfileRepository
import org.helix.addons.mineclash.profile.KronenRewards
import org.helix.addons.mineclash.profile.StatsReporter
import org.helix.addons.mineclash.queue.Matchmaker
import org.helix.addons.mineclash.war.ArrivalDecision
import org.helix.addons.mineclash.war.DocumentWarStore
import org.helix.addons.mineclash.war.PostgresWarStore
import org.helix.addons.mineclash.war.Msg
import org.helix.addons.mineclash.war.ServiceDirectory
import org.helix.addons.mineclash.war.WarArrival
import org.helix.addons.mineclash.war.WarCoordinator
import org.helix.addons.mineclash.war.WarEffectRunner
import org.helix.addons.mineclash.war.WarProtocol
import org.helix.addons.mineclash.war.WarSettings
import org.helix.addons.mineclash.war.WarStore
import org.helix.api.addon.PlayerListener
import org.helix.api.message.Messages
import org.helix.api.player.OnlinePlayer
import org.helix.api.release.ReleaseReport
import org.helix.api.release.ReleaseReset
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Permission for the write config actions (panel/REST/console), granted through the permissions addon. */
private const val ADMIN_PERMISSION = "mineclash.admin"

/**
 * MineClash node addon: the single source of truth for the card battle game.
 *
 * - Configuration (cards, chests, buildings, perks, balancing, server roles) in the config store,
 *   editable in the dashboard panel and served to the Paper servers as one bundle.
 * - Player profiles and match history in PostgreSQL (schema `mineclash`) or, without a database,
 *   in the addon document store.
 * - Arena matchmaking: services queue players and poll for matches to host and players to move.
 * - Clan membership resolved through the `helix.clan` addon.
 * - Clan wars (`/clanwar`): challenges, the war coordinator that hands wars to the `MineClash-War` servers and
 *   their results (contract `Mineclash/docs/CLANWAR-CONTRACT.md`).
 *
 * All `mineclash.*` actions are bridge-invocable so the Paper component reaches them with its
 * per-service token via `POST /internal/action`.
 */
class MineClashAddon : AddonBase() {
    private val logger = LoggerFactory.getLogger(MineClashAddon::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var config: ConfigStore
    private lateinit var profiles: ProfileRepository
    private lateinit var matchmaker: Matchmaker
    private lateinit var clans: ClanLookup
    private lateinit var stats: StatsReporter
    private lateinit var kronen: KronenRewards
    private lateinit var messages: Messages
    /** Null while the configured PostgreSQL store is unreachable (clan wars refuse instead of splitting storage). */
    @Volatile
    private var warStore: WarStore? = null

    /** Null while [warStore] is unavailable; created once it opens. */
    @Volatile
    private var wars: WarCoordinator? = null
    private lateinit var effects: WarEffectRunner
    private lateinit var services: ServiceDirectory
    private var effectThread: ExecutorService? = null
    private var startThread: ExecutorService? = null
    private var warTicker: ScheduledExecutorService? = null

    /** Registers stores, actions and the dashboard panel. */
    override fun enable() {
        messages = loadMessages()
        config = ConfigStore(context.storage())
        profiles = openProfiles()
        matchmaker = Matchmaker(timeoutMillis = (config.setting("queue.timeoutSeconds")?.toLongOrNull() ?: 600L) * 1000)
        clans = ClanLookup(context.actions)
        stats = StatsReporter(context.actions) { uuid -> profiles.load(uuid)?.name?.takeIf { it.isNotBlank() } }
        kronen = KronenRewards(
            context.actions,
            perMatch = { config.setting(KronenRewards.PER_MATCH)?.toLongOrNull() ?: 10L },
            perWin = { config.setting(KronenRewards.PER_WIN)?.toLongOrNull() ?: 40L },
            perWar = { config.setting(KronenRewards.PER_WAR)?.toLongOrNull() ?: 30L },
            perWarWin = { config.setting(KronenRewards.PER_WAR_WIN)?.toLongOrNull() ?: 120L },
        )
        registerConfigActions()
        registerProfileActions()
        registerQueueActions()
        registerWar()
        action("mineclash.clan.of", "Machine API: clan membership of a player as JSON or 'null'.", "mineclash.clan.of <player>", bridgeInvocable = true) { inv ->
            val player = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: mineclash.clan.of <player>")
            ActionResult.ok(clans.clanOf(player)?.let { json.encodeToString(it) } ?: "null")
        }
        panel(id = "mineclash", title = "MineClash", resource = "/panel.html")
        logger.info("MineClash addon enabled: {} settings, collections {}", config.settings().size, config.collectionNames())
    }

    /** Stops the clan-war tasks and closes the repositories. */
    override fun onDisable() {
        warTicker?.shutdownNow()
        effectThread?.shutdown()
        startThread?.shutdownNow()
        runCatching { effectThread?.awaitTermination(2, TimeUnit.SECONDS) }
        warStore?.close()
        profiles.close()
    }

    private fun intSetting(key: String, default: Int): Int = config.setting(key)?.toIntOrNull() ?: default

    private fun warSettings(): WarSettings {
        return WarSettings(
            prepareSeconds = intSetting("war.prepareSeconds", 120),
            maxPlayersPerClan = intSetting("war.maxPlayersPerClan", 10),
            abandonMinutes = intSetting("war.abandonMinutes", 15),
            challengeExpirySeconds = intSetting("war.challengeExpirySeconds", 300),
            queueTimeoutMinutes = intSetting("war.queueTimeoutMinutes", 10),
            serverLostSeconds = intSetting("war.serverLostSeconds", 60),
            warTask = config.setting("war.task")?.takeIf { it.isNotBlank() } ?: "MineClash-War",
            gameTask = config.setting("queue.gameTask")?.takeIf { it.isNotBlank() } ?: "MineClash-Game",
        )
    }

    /**
     * The war store: PostgreSQL when the node runs on it, else the document store. A configured but unreachable
     * database gives null – no silent switch to the document store, which would hide the active wars in PostgreSQL
     * (clans would look free, hosted wars would be orphaned); [ensureWars] retries every minute.
     */
    private fun openWarStore(): WarStore? {
        val connection = context.storageConnection()
        if (connection != null && connection.mode.equals("postgres", ignoreCase = true)) {
            return runCatching<WarStore> { PostgresWarStore(connection) }
                .onFailure { logger.error("Clan wars unavailable: PostgreSQL war store cannot be opened ({}); retrying every minute", it.message) }
                .getOrNull()
        }
        return DocumentWarStore(context.storage())
    }

    /** Opens the war store and the coordinator when they are not up yet (start and every minute after a failure). */
    @Synchronized
    private fun ensureWars(): WarCoordinator? {
        wars?.let { return it }
        val store = warStore ?: openWarStore() ?: return null
        warStore = store
        val coordinator = runCatching {
            WarCoordinator(
                store = store,
                clans = clans,
                online = { context.onlinePlayers() },
                uuidOf = { name -> context.resolvePlayerUuid(name) },
                settings = ::warSettings,
                returnTo = { services.returnTo() },
            )
        }.getOrElse {
            logger.error("Clan wars unavailable: active wars cannot be loaded ({})", it.message)
            if (store is PostgresWarStore) {
                runCatching { store.close() }
                warStore = null
            }
            return null
        }
        wars = coordinator
        effects.run(coordinator.pendingPayouts())
        logger.info("Clan wars ready ({} active)", coordinator.active().size)
        return coordinator
    }

    private fun unavailable(player: String?): ActionResult =
        ActionResult.error(effects.render(player ?: "", Msg("clanwar.unavailable")))

    /** Clan wars: coordinator, effect thread, periodic tick, player command, war-server machine API, join reminder. */
    private fun registerWar() {
        val settings = warSettings()
        services = ServiceDirectory(context.actions) { warSettings().gameTask }
        val thread = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "mineclash-war-effects").apply { isDaemon = true } }
        effectThread = thread
        val starts = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "mineclash-war-service-start").apply { isDaemon = true } }
        startThread = starts
        effects = WarEffectRunner(context.actions, messages, kronen, thread, starts) { warId -> wars?.markPaid(warId) }
        ensureWars()
        val ticker = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "mineclash-war-tick").apply { isDaemon = true } }
        warTicker = ticker
        ticker.scheduleAtFixedRate({ runCatching { wars?.let { effects.run(it.tick()) } }.onFailure { logger.warn("clan-war tick failed: {}", it.message) } }, 5, 5, TimeUnit.SECONDS)
        ticker.scheduleAtFixedRate({ runCatching { if (wars == null) ensureWars() } }, 60, 60, TimeUnit.SECONDS)
        ticker.scheduleAtFixedRate({ runCatching { services.refresh() } }, 1, 30, TimeUnit.SECONDS)
        context.registerPlayerListener(
            /** Reminds owners/officers of open incoming challenges when they join the network. */
            object : PlayerListener {
                override fun onJoin(player: OnlinePlayer) {
                    runCatching { wars?.let { effects.run(it.onJoin(player.name)) } }
                }
            },
        )
        val gameAndWar = setOf(settings.gameTask, settings.warTask)
        val warOnly = setOf(settings.warTask)
        action(
            "clanwar",
            "Clan wars: challenge another clan, accept, deny, cancel, join the war world, show the status.",
            "clanwar challenge|accept|deny|cancel|join|status [tag]",
            playerCommand = true,
            permission = "helix.clan",
        ) { inv -> runWarCommand(inv.arguments) }
        action(
            "mineclash.war.command",
            "Machine API: /clanwar for the clan menu (player, sub command, tag).",
            "mineclash.war.command <player> <sub> [tag]",
            bridgeInvocable = true,
            bridgeTasks = gameAndWar,
        ) { inv ->
            val player = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: mineclash.war.command <player> <sub> [tag]")
            if (!context.hasPermission(player, "helix.clan")) return@action ActionResult.error("no permission")
            runWarCommand(inv.arguments)
        }
        action(
            "mineclash.clan.command",
            "Machine API: runs /clan for a player (clan menu buttons).",
            "mineclash.clan.command <player> <args…>",
            bridgeInvocable = true,
            bridgeTasks = gameAndWar,
        ) { inv ->
            val player = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: mineclash.clan.command <player> <args…>")
            if (!context.hasPermission(player, "helix.clan")) return@action ActionResult.error("no permission")
            clans.invalidate()
            context.actions.invoke(ActionInvocation("clan", inv.arguments))
        }
        action(
            "mineclash.war.poll",
            "Machine API: a war server's status in, its complete desired state out.",
            "mineclash.war.poll <serviceId> <statusJson…>",
            bridgeInvocable = true,
            bridgeTasks = warOnly,
        ) { inv ->
            val service = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: mineclash.war.poll <serviceId> <statusJson…>")
            val status = runCatching { WarProtocol.status(inv.arguments.drop(1).joinToString(" ")) }
                .getOrElse { return@action ActionResult.error("invalid status json: ${it.message}") }
            val coordinator = wars ?: return@action ActionResult.error("clan wars unavailable")
            val answer = coordinator.poll(service, status)
            effects.run(answer.effects)
            ActionResult.ok(WarProtocol.encode(answer.state))
        }
        action(
            "mineclash.war.arrive",
            "Machine API: may a player log in to this war server (rosters clan members within the limit).",
            "mineclash.war.arrive <serviceId> <uuid> <name>",
            bridgeInvocable = true,
            bridgeTasks = warOnly,
        ) { inv ->
            val a = inv.arguments
            if (a.size < 3) return@action ActionResult.error("usage: mineclash.war.arrive <serviceId> <uuid> <name>")
            val decision = wars?.arrive(a[0], a[1], a[2]) ?: ArrivalDecision(refusal = Msg("clanwar.unavailable"))
            val arrival = decision.refusal?.let { WarArrival(ok = false, reason = effects.renderScreen(a[2], it)) }
                ?: WarArrival(ok = true, warId = decision.warId, team = decision.team)
            ActionResult.ok(WarProtocol.encode(arrival))
        }
        action(
            "mineclash.war.finish",
            "Machine API: result of a war (idempotent per war id).",
            "mineclash.war.finish <serviceId> <resultJson…>",
            bridgeInvocable = true,
            bridgeTasks = warOnly,
        ) { inv ->
            val service = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: mineclash.war.finish <serviceId> <resultJson…>")
            val result = runCatching { WarProtocol.result(inv.arguments.drop(1).joinToString(" ")) }
                .getOrElse { return@action ActionResult.error("invalid result json: ${it.message}") }
            val coordinator = wars ?: return@action ActionResult.error("clan wars unavailable")
            val answer = coordinator.finish(service, result)
            effects.run(answer.effects)
            ActionResult.ok(answer.answer)
        }
        action("mineclash.war.status", "Active clan wars, open challenges and war servers as JSON.", "mineclash.war.status", permission = ADMIN_PERMISSION) { inv ->
            val coordinator = wars ?: return@action unavailable(inv.actor)
            ActionResult.ok(coordinator.snapshot().toString())
        }
        action("mineclash.war.cancel", "Cancels an active clan war without a result.", "mineclash.war.cancel <warId>", permission = ADMIN_PERMISSION) { inv ->
            val warId = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: mineclash.war.cancel <warId>")
            val coordinator = wars ?: return@action unavailable(inv.actor)
            val outcome = coordinator.adminCancel(warId)
            effects.run(outcome.effects)
            ActionResult.ok(*outcome.replies.map { effects.render(inv.actor ?: "", it) }.toTypedArray())
        }
    }

    private fun runWarCommand(arguments: List<String>): ActionResult {
        val player = arguments.firstOrNull() ?: return ActionResult.error("usage: clanwar <sub> [tag]")
        val coordinator = wars ?: return unavailable(player)
        val outcome = coordinator.command(player, arguments.drop(1))
        effects.run(outcome.effects)
        return ActionResult.ok(*outcome.replies.map { effects.render(player, it) }.toTypedArray())
    }

    private fun openProfiles(): ProfileRepository {
        val connection = context.storageConnection()
        if (connection != null && connection.mode.equals("postgres", ignoreCase = true)) {
            return runCatching { PostgresProfileRepository(connection) }
                .onFailure { logger.warn("PostgreSQL profile repository unavailable, using document store: {}", it.message) }
                .getOrElse { DocumentProfileRepository(context.storage()) }
        }
        return DocumentProfileRepository(context.storage())
    }

    private fun registerConfigActions() {
        action("mineclash.config.bundle", "Machine API: the whole game configuration as one JSON bundle.", bridgeInvocable = true) {
            ActionResult.ok(config.bundleJson())
        }
        action("mineclash.config.version", "Machine API: current configuration version.", bridgeInvocable = true) {
            ActionResult.ok(config.version().toString())
        }
        action("mineclash.config.export", "Settings with types and defaults as JSON (panel).") {
            ActionResult.ok(config.settingsExportJson())
        }
        action("mineclash.config.set", "Sets a setting; the value is validated against its type.", "mineclash.config.set <key> <value…>", permission = ADMIN_PERMISSION) { inv ->
            val key = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: mineclash.config.set <key> <value…>")
            val raw = inv.arguments.drop(1).joinToString(" ")
            runCatching { config.setSetting(key, raw) }
                .fold({ ActionResult.ok("$key = $it") }, { ActionResult.error(it.message ?: "invalid value") })
        }
        action("mineclash.config.reset", "Resets a setting to its default.", "mineclash.config.reset <key>", permission = ADMIN_PERMISSION) { inv ->
            val key = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: mineclash.config.reset <key>")
            config.resetSetting(key)?.let { ActionResult.ok("$key = $it") } ?: ActionResult.error("no default for '$key'")
        }
        action("mineclash.config.collections", "Collection names as JSON (panel).") {
            ActionResult.ok(json.encodeToString(config.collectionNames()))
        }
        action("mineclash.config.docs", "Documents of a collection as JSON (panel).", "mineclash.config.docs <collection>") { inv ->
            val collection = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: mineclash.config.docs <collection>")
            ActionResult.ok(json.encodeToString(config.documents(collection)))
        }
        action("mineclash.config.doc.set", "Creates or replaces a document from JSON.", "mineclash.config.doc.set <collection> <id> <sortOrder> <json…>", permission = ADMIN_PERMISSION) { inv ->
            val args = inv.arguments
            if (args.size < 4) return@action ActionResult.error("usage: mineclash.config.doc.set <collection> <id> <sortOrder> <json…>")
            val sortOrder = args[2].toIntOrNull() ?: return@action ActionResult.error("sortOrder must be a number")
            val body = runCatching { json.parseToJsonElement(args.drop(3).joinToString(" ")).jsonObject }
                .getOrElse { return@action ActionResult.error("body must be a JSON object") }
            config.putDocument(args[0], ConfigDocument(args[1], sortOrder, body))
            ActionResult.ok("stored ${args[0]}/${args[1]} (version ${config.version()})")
        }
        action("mineclash.config.doc.remove", "Removes a document.", "mineclash.config.doc.remove <collection> <id>", permission = ADMIN_PERMISSION) { inv ->
            if (inv.arguments.size < 2) return@action ActionResult.error("usage: mineclash.config.doc.remove <collection> <id>")
            if (config.removeDocument(inv.arguments[0], inv.arguments[1])) ActionResult.ok("removed") else ActionResult.error("no such document")
        }
        action("mineclash.config.seed", "Inserts missing defaults; 'force' overwrites everything.", "mineclash.config.seed [force]", permission = ADMIN_PERMISSION) { inv ->
            ActionResult.ok("${config.seed(force = inv.arguments.firstOrNull().equals("force", true))} entries written")
        }
    }

    private fun registerProfileActions() {
        action("mineclash.profile.load", "Machine API: loads (or creates) a player profile as JSON.", "mineclash.profile.load <uuid> <name>", bridgeInvocable = true) { inv ->
            val uuid = inv.arguments.getOrNull(0) ?: return@action ActionResult.error("usage: mineclash.profile.load <uuid> <name>")
            val name = inv.arguments.getOrNull(1) ?: ""
            val existing = profiles.load(uuid)
            val profile = existing?.let { if (name.isNotBlank() && it.name != name) it.copy(name = name) else it }
                ?: PlayerProfile.starter(uuid, name, starterDeck(), config.setting("deck.maxSize")?.toIntOrNull() ?: 8).also(profiles::save)
            ActionResult.ok(json.encodeToString(profile))
        }
        action("mineclash.profile.save", "Machine API: stores a player profile from JSON.", "mineclash.profile.save <uuid> <json…>", bridgeInvocable = true) { inv ->
            val uuid = inv.arguments.getOrNull(0) ?: return@action ActionResult.error("usage: mineclash.profile.save <uuid> <json…>")
            val profile = runCatching { json.decodeFromString<PlayerProfile>(inv.arguments.drop(1).joinToString(" ")) }
                .getOrElse { return@action ActionResult.error("invalid profile json: ${it.message}") }
            if (profile.uuid != uuid) return@action ActionResult.error("uuid mismatch")
            profiles.save(profile.copy(updatedAt = System.currentTimeMillis()))
            ActionResult.ok("saved")
        }
        action("mineclash.match.record", "Machine API: records a finished match.", "mineclash.match.record <json…>", bridgeInvocable = true) { inv ->
            val record = runCatching { json.decodeFromString<MatchRecord>(inv.arguments.joinToString(" ")) }
                .getOrElse { return@action ActionResult.error("invalid match json: ${it.message}") }
            profiles.recordMatch(record)
            stats.report(record) // network-wide stats (helix.stats), when installed
            kronen.pay(record) // network currency (helix.economy), when installed
            ActionResult.ok("recorded")
        }
        action(
            "mineclash" + ReleaseReset.ACTION_SUFFIX,
            "Release reset: deletes every MineClash profile and the match history (keeps configuration).",
            "mineclash.release.reset <plan|run>",
            permission = ReleaseReset.PERMISSION,
        ) { inv ->
            val dryRun = ReleaseReset.dryRun(inv.arguments)
                ?: return@action ActionResult.error("usage", "mineclash.release.reset <plan|run>")
            ActionResult.ok(
                ReleaseReport(
                    addon = "mineclash",
                    mode = if (dryRun) ReleaseReset.PLAN else ReleaseReset.RUN,
                    deleted = profiles.wipe(dryRun) + (warStore?.wipe(dryRun) ?: emptyMap()),
                    kept = mapOf("config" to "settings, cards, chests, buildings, perks and servers"),
                ).toJson(),
            )
        }
        action("mineclash.top", "Best players by roses as JSON.", "mineclash.top [limit]") { inv ->
            val limit = inv.arguments.firstOrNull()?.toIntOrNull() ?: 10
            ActionResult.ok(json.encodeToString(profiles.topByRoses(limit.coerceIn(1, 100)).map { mapOf("uuid" to it.first, "name" to it.second, "roses" to it.third.toString()) }))
        }
    }

    private fun registerQueueActions() {
        action("mineclash.queue.join", "Machine API: queues a player for a match mode.", "mineclash.queue.join <uuid> <name> <mode> <serviceId>", bridgeInvocable = true) { inv ->
            val a = inv.arguments
            if (a.size < 4) return@action ActionResult.error("usage: mineclash.queue.join <uuid> <name> <mode> <serviceId>")
            matchmaker.join(a[0], a[1], a[2], a[3])?.let { ActionResult.error(it) } ?: ActionResult.ok("queued")
        }
        action("mineclash.queue.leave", "Machine API: removes a player from the queue.", "mineclash.queue.leave <uuid>", bridgeInvocable = true) { inv ->
            val uuid = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: mineclash.queue.leave <uuid>")
            if (matchmaker.leave(uuid)) ActionResult.ok("left") else ActionResult.error("not queued")
        }
        action("mineclash.queue.poll", "Machine API: matches to host and players to move for a service.", "mineclash.queue.poll <serviceId>", bridgeInvocable = true) { inv ->
            val service = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: mineclash.queue.poll <serviceId>")
            ActionResult.ok(json.encodeToString(matchmaker.poll(service)))
        }
        action("mineclash.queue.status", "Queued players per mode.") {
            val queued = matchmaker.queued()
            ActionResult.ok(if (queued.isEmpty()) "queue is empty" else queued.entries.joinToString(", ") { "${it.key}: ${it.value}" })
        }
    }

    private fun starterDeck(): List<String> =
        config.setting("deck.starter")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
