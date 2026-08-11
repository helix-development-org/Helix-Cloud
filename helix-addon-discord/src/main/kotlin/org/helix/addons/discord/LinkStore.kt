package org.helix.addons.discord

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * A confirmed Discord-to-Minecraft account link.
 *
 * @property discordId Discord user id.
 * @property discordName Discord user name at link time, for display only.
 * @property uuid Minecraft account uuid the link is keyed on.
 * @property playerName Minecraft name at link time, for display only —
 *   permission checks re-resolve the current name through the identity
 *   registry so renames cannot detach a link from its account.
 * @property linkedAtEpochMs epoch millis of the link.
 * @property linkedBy how the link was established: `game-code`,
 *   `discord-code` or the bootstrapping actor's name.
 */
@Serializable
data class DiscordLink(
    val discordId: String,
    val discordName: String,
    val uuid: String,
    val playerName: String,
    val linkedAtEpochMs: Long,
    val linkedBy: String,
)

/**
 * Outcome of a link attempt.
 */
sealed class LinkOutcome {
    /**
     * The link was established.
     *
     * @property link the new link.
     */
    data class Linked(val link: DiscordLink) : LinkOutcome()

    /** The code is unknown or expired. */
    data object InvalidCode : LinkOutcome()

    /**
     * One of the two accounts is already linked.
     *
     * @property existing the conflicting link.
     */
    data class AlreadyLinked(val existing: DiscordLink) : LinkOutcome()
}

/**
 * Persists Discord-to-Minecraft account links and manages the short-lived
 * link codes of both flows: a code created in-game and redeemed in Discord,
 * or a code created in Discord and redeemed in-game.
 *
 * Links are stored one per Discord user and one per Minecraft uuid; a new
 * link is rejected while either side is already linked. Codes are held in
 * memory only — a node restart simply invalidates them.
 *
 * @property storage addon-scoped document store holding the links.
 * @property ttlMs supplier of the code lifetime in millis.
 * @property now clock, injectable for tests.
 * @property codeFactory code generator, injectable for tests.
 */
class LinkStore(
    private val storage: AddonStorage,
    private val ttlMs: () -> Long = { 300_000L },
    private val now: () -> Long = System::currentTimeMillis,
    private val codeFactory: () -> String = ::randomCode,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val links = linkedMapOf<String, DiscordLink>()
    private val gameCodes = ConcurrentHashMap<String, GameCode>()
    private val discordCodes = ConcurrentHashMap<String, DiscordCode>()

    private data class GameCode(val uuid: String, val playerName: String, val expiresAt: Long)

    private data class DiscordCode(val discordId: String, val discordName: String, val expiresAt: Long)

    init {
        val raw = storage.read(DOCUMENT)
        if (raw != null) {
            runCatching { json.decodeFromString<List<DiscordLink>>(raw) }
                .getOrNull()
                ?.forEach { links[it.discordId] = it }
        }
    }

    /**
     * Creates a code for the in-game flow: the player proved account
     * ownership by running the command, the code is redeemed in Discord.
     * A previous code of the same account is replaced.
     *
     * @param uuid the player's uuid.
     * @param playerName the player's current name.
     * @return the code to enter in Discord.
     */
    @Synchronized
    fun createGameCode(uuid: String, playerName: String): String {
        gameCodes.entries.removeIf { it.value.uuid == uuid || it.value.expiresAt <= now() }
        val code = codeFactory()
        gameCodes[code] = GameCode(uuid, playerName, now() + ttlMs())
        return code
    }

    /**
     * Creates a code for the Discord flow: the Discord user requested it,
     * the code is redeemed in-game. A previous code of the same user is
     * replaced.
     *
     * @param discordId the Discord user id.
     * @param discordName the Discord user name.
     * @return the code to enter in-game.
     */
    @Synchronized
    fun createDiscordCode(discordId: String, discordName: String): String {
        discordCodes.entries.removeIf { it.value.discordId == discordId || it.value.expiresAt <= now() }
        val code = codeFactory()
        discordCodes[code] = DiscordCode(discordId, discordName, now() + ttlMs())
        return code
    }

    /**
     * Redeems an in-game-created code from the Discord side.
     *
     * @param code the code the Discord user entered.
     * @param discordId the redeeming Discord user id.
     * @param discordName the redeeming Discord user name.
     * @return the outcome; on success the code is consumed.
     */
    @Synchronized
    fun redeemGameCode(code: String, discordId: String, discordName: String): LinkOutcome {
        val pending = gameCodes[code.trim().uppercase()]?.takeIf { it.expiresAt > now() }
            ?: return LinkOutcome.InvalidCode
        val conflict = links[discordId] ?: links.values.firstOrNull { it.uuid == pending.uuid }
        if (conflict != null) {
            return LinkOutcome.AlreadyLinked(conflict)
        }
        gameCodes.remove(code.trim().uppercase())
        return LinkOutcome.Linked(put(discordId, discordName, pending.uuid, pending.playerName, "game-code"))
    }

    /**
     * Redeems a Discord-created code from the in-game side.
     *
     * @param code the code the player entered.
     * @param uuid the redeeming player's uuid.
     * @param playerName the redeeming player's current name.
     * @return the outcome; on success the code is consumed.
     */
    @Synchronized
    fun redeemDiscordCode(code: String, uuid: String, playerName: String): LinkOutcome {
        val pending = discordCodes[code.trim().uppercase()]?.takeIf { it.expiresAt > now() }
            ?: return LinkOutcome.InvalidCode
        val conflict = links[pending.discordId] ?: links.values.firstOrNull { it.uuid == uuid }
        if (conflict != null) {
            return LinkOutcome.AlreadyLinked(conflict)
        }
        discordCodes.remove(code.trim().uppercase())
        return LinkOutcome.Linked(put(pending.discordId, pending.discordName, uuid, playerName, "discord-code"))
    }

    /**
     * Establishes a link without a code — the dashboard/CLI bootstrap path
     * for the very first admins, since the bot itself requires a linked
     * account for everything.
     *
     * @param discordId Discord user id.
     * @param uuid player uuid.
     * @param playerName player name for display.
     * @param actor who established the link, for the audit trail.
     * @return the outcome.
     */
    @Synchronized
    fun setLink(discordId: String, uuid: String, playerName: String, actor: String): LinkOutcome {
        val conflict = links[discordId] ?: links.values.firstOrNull { it.uuid == uuid }
        if (conflict != null) {
            return LinkOutcome.AlreadyLinked(conflict)
        }
        return LinkOutcome.Linked(put(discordId, playerName, uuid, playerName, actor))
    }

    /**
     * Removes the link of a Discord user.
     *
     * @param discordId Discord user id.
     * @return the removed link, or `null` when none existed.
     */
    @Synchronized
    fun unlinkDiscord(discordId: String): DiscordLink? {
        val removed = links.remove(discordId)
        if (removed != null) {
            persist()
        }
        return removed
    }

    /**
     * Removes the link of a Minecraft account.
     *
     * @param uuid player uuid.
     * @return the removed link, or `null` when none existed.
     */
    @Synchronized
    fun unlinkPlayer(uuid: String): DiscordLink? {
        val link = links.values.firstOrNull { it.uuid == uuid } ?: return null
        return unlinkDiscord(link.discordId)
    }

    /**
     * The link of a Discord user.
     *
     * @param discordId Discord user id.
     * @return the link, or `null`.
     */
    @Synchronized
    fun byDiscord(discordId: String): DiscordLink? = links[discordId]

    /**
     * The link of a Minecraft account.
     *
     * @param uuid player uuid.
     * @return the link, or `null`.
     */
    @Synchronized
    fun byPlayer(uuid: String): DiscordLink? = links.values.firstOrNull { it.uuid == uuid }

    /**
     * All confirmed links.
     *
     * @return links sorted by player name.
     */
    @Synchronized
    fun all(): List<DiscordLink> = links.values.sortedBy { it.playerName.lowercase() }

    private fun put(
        discordId: String,
        discordName: String,
        uuid: String,
        playerName: String,
        linkedBy: String,
    ): DiscordLink {
        val link = DiscordLink(discordId, discordName, uuid, playerName, now(), linkedBy)
        links[discordId] = link
        persist()
        return link
    }

    private fun persist() {
        storage.write(DOCUMENT, json.encodeToString(links.values.toList()))
    }

    private companion object {
        /** Storage document key holding all links. */
        const val DOCUMENT = "links"

        /** Code alphabet without look-alike characters. */
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        val random = SecureRandom()

        /** Generates an 8-character link code. */
        fun randomCode(): String =
            (1..8).joinToString("") { ALPHABET[random.nextInt(ALPHABET.length)].toString() }
    }
}
