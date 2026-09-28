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
import org.helix.addons.mineclash.queue.Matchmaker
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.slf4j.LoggerFactory

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

    /** Registers stores, actions and the dashboard panel. */
    override fun enable() {
        loadMessages()
        config = ConfigStore(context.storage())
        profiles = openProfiles()
        matchmaker = Matchmaker(timeoutMillis = (config.setting("queue.timeoutSeconds")?.toLongOrNull() ?: 600L) * 1000)
        clans = ClanLookup(context.actions)
        registerConfigActions()
        registerProfileActions()
        registerQueueActions()
        action("mineclash.clan.of", "Machine API: clan membership of a player as JSON or 'null'.", "mineclash.clan.of <player>", bridgeInvocable = true) { inv ->
            val player = inv.arguments.firstOrNull() ?: return@action ActionResult.error("usage: mineclash.clan.of <player>")
            ActionResult.ok(clans.clanOf(player)?.let { json.encodeToString(it) } ?: "null")
        }
        panel(id = "mineclash", title = "MineClash", resource = "/panel.html")
        logger.info("MineClash addon enabled: {} settings, collections {}", config.settings().size, config.collectionNames())
    }

    /** Closes the profile repository. */
    override fun onDisable() {
        profiles.close()
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
            ActionResult.ok("recorded")
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
