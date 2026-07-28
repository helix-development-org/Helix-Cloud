package org.helix.node.whitelist

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Operator-configurable network whitelist, independent of any per-task
 * maintenance mode.
 *
 * While [enabled], only accounts on the allow-list may join through any
 * proxy — enforced generically via a join gate registered in the node
 * launcher, the same mechanism the ban addon uses. Persisted as its own
 * JSON file so the toggle and the allow-list survive restarts without
 * requiring the permissions or bans addon to be installed.
 *
 * @property file path of the `whitelist.json` file.
 */
class WhitelistStore(private val file: Path) {
    private val logger = LoggerFactory.getLogger(WhitelistStore::class.java)
    private val json = Json { prettyPrint = true }

    @Volatile
    private var enabled: Boolean = false
    private val entries = linkedSetOf<String>()

    init {
        load()
    }

    /**
     * Whether the whitelist currently gates joins.
     *
     * @return `true` when only allow-listed accounts may join.
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Enables or disables enforcement.
     *
     * @param value the new state.
     */
    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        persist()
    }

    /**
     * Adds an account to the allow-list.
     *
     * @param name player name, matched case-insensitively.
     * @return `false` when the account was already listed.
     */
    @Synchronized
    fun add(name: String): Boolean {
        val added = entries.add(name.lowercase())
        if (added) {
            persist()
        }
        return added
    }

    /**
     * Removes an account from the allow-list.
     *
     * @param name player name, matched case-insensitively.
     * @return `false` when the account was not listed.
     */
    @Synchronized
    fun remove(name: String): Boolean {
        val removed = entries.remove(name.lowercase())
        if (removed) {
            persist()
        }
        return removed
    }

    /**
     * Whether an account is on the allow-list.
     *
     * @param name player name, matched case-insensitively.
     * @return `true` when listed.
     */
    fun contains(name: String): Boolean = entries.contains(name.lowercase())

    /**
     * Lists every allow-listed account.
     *
     * @return account names sorted alphabetically.
     */
    @Synchronized
    fun all(): List<String> = entries.sorted()

    @Synchronized
    private fun load() {
        if (!Files.exists(file)) {
            return
        }
        runCatching {
            val document = json.decodeFromString<WhitelistDocument>(Files.readString(file))
            enabled = document.enabled
            entries.addAll(document.entries.map { it.lowercase() })
        }.onFailure { logger.warn("Could not load whitelist: {}", it.message) }
    }

    @Synchronized
    private fun persist() {
        runCatching {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temp, json.encodeToString(WhitelistDocument(enabled, entries.sorted())))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { logger.warn("Could not persist whitelist: {}", it.message) }
    }
}

/**
 * Persisted shape of the whitelist file.
 *
 * @property enabled whether enforcement is active.
 * @property entries allow-listed account names, lowercase.
 */
@Serializable
data class WhitelistDocument(
    val enabled: Boolean = false,
    val entries: List<String> = emptyList(),
)
