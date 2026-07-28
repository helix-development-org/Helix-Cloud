package org.helix.addons.profile

import kotlinx.serialization.json.Json
import org.helix.api.storage.AddonStorage

/**
 * Persistence for every player's chosen profile-setting values.
 *
 * The profile addon is the single source of truth for setting VALUES —
 * contributing addons only describe what can be chosen (see
 * `ProfileSettingProvider`); this store is where the actual choice lives,
 * so a player's whole profile stays in one place no matter how many
 * addons contribute to it.
 *
 * @property storage addon-scoped document store.
 */
class ProfileStore(private val storage: AddonStorage) {
    private val json = Json { prettyPrint = true }
    private val values = mutableMapOf<String, MutableMap<String, String>>()

    init {
        storage.read(DOCUMENT)?.let { raw ->
            json.decodeFromString<ProfileDocument>(raw).values.forEach { (player, settings) ->
                values[player] = settings.toMutableMap()
            }
        }
    }

    /**
     * The chosen value of one setting.
     *
     * @param player player name, matched case-insensitively.
     * @param owner the addon id that registered the setting.
     * @param key the setting's key.
     * @return the chosen value, or `null` when the player never set one.
     */
    fun get(player: String, owner: String, key: String): String? =
        values[player.lowercase()]?.get(storageKey(owner, key))

    /**
     * All of a player's chosen values.
     *
     * @param player player name, matched case-insensitively.
     * @return `"<owner>:<key>"` to chosen value.
     */
    fun allFor(player: String): Map<String, String> = values[player.lowercase()]?.toMap() ?: emptyMap()

    /**
     * Sets a player's chosen value for a setting.
     *
     * @param player player name, matched case-insensitively.
     * @param owner the addon id that registered the setting.
     * @param key the setting's key.
     * @param value the value to persist.
     */
    fun set(player: String, owner: String, key: String, value: String) {
        values.getOrPut(player.lowercase()) { mutableMapOf() }[storageKey(owner, key)] = value
        persist()
    }

    /**
     * Clears a player's chosen value for a setting, reverting it to
     * whatever default the owning addon's descriptor declares.
     *
     * @param player player name, matched case-insensitively.
     * @param owner the addon id that registered the setting.
     * @param key the setting's key.
     * @return `true` when a value was actually removed.
     */
    fun clear(player: String, owner: String, key: String): Boolean {
        val removed = values[player.lowercase()]?.remove(storageKey(owner, key)) != null
        if (removed) {
            persist()
        }
        return removed
    }

    private fun storageKey(owner: String, key: String) = "$owner:$key"

    private fun persist() {
        storage.write(DOCUMENT, json.encodeToString(ProfileDocument(values)))
    }

    private companion object {
        /** Document key holding every player's chosen settings. */
        const val DOCUMENT = "profile"
    }
}
