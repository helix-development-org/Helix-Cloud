package org.helix.bridge.paper

/**
 * Pure chat-moderation logic consumed by [HelixPaperBridgePlugin.onChat].
 *
 * Chat is rendered fully bridge-side with no per-message round trip to the
 * node, so mute enforcement and the word filter both work off a
 * periodically-synced bridge-value snapshot instead of a live query.
 * Extracted from the plugin (which needs a running Bukkit server to
 * instantiate) so the matching rules are unit-testable on their own.
 */
object ChatModerationGate {
    /** Splits chat text into words for the blocklist filter (letters/digits only). */
    private val NON_WORD_CHARACTERS = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Whether a player carries an active mute per the synced mute map.
     *
     * @param mutes lowercase player name to expiry epoch millis, `0` for permanent.
     * @param playerName the sender, matched case-insensitively.
     * @param nowEpochMs current epoch millis, compared against the synced
     *   expiry so a mute that expired since the last sync is not enforced
     *   past its time (the bridge trusts its own clock, not a stale flag).
     * @return `true` while an unexpired mute exists for the player.
     */
    fun isMuted(mutes: Map<String, Long>, playerName: String, nowEpochMs: Long): Boolean {
        val expiresAt = mutes[playerName.lowercase()] ?: return false
        return expiresAt == 0L || expiresAt > nowEpochMs
    }

    /**
     * Whether a message contains a blocked word.
     *
     * Matching is whole-word (split on non-letter/digit runs) and
     * case-insensitive, not a raw substring search, so a blocked word never
     * blocks an unrelated word that merely contains it as a substring.
     *
     * @param blocklist configured banned words, any case.
     * @param plainText the plain-text message.
     * @return `true` when any word of the message is on the blocklist.
     */
    fun isBlocked(blocklist: List<String>, plainText: String): Boolean {
        if (blocklist.isEmpty()) return false
        val blockedLower = blocklist.mapTo(HashSet()) { it.lowercase() }
        val tokens = plainText.lowercase().split(NON_WORD_CHARACTERS).filter { it.isNotEmpty() }
        return tokens.any { it in blockedLower }
    }

    /**
     * Resolves a bilingual (`en`/`de`) text map by locale language, falling
     * back to English and then to any available entry.
     *
     * @param texts language code to text.
     * @param localeLanguage the viewer's client-reported locale language.
     * @return the resolved text, or empty when [texts] itself is empty.
     */
    fun localize(texts: Map<String, String>, localeLanguage: String): String =
        texts[localeLanguage] ?: texts["en"] ?: texts.values.firstOrNull().orEmpty()
}
