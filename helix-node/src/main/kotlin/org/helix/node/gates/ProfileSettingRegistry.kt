package org.helix.node.gates

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.helix.api.addon.ProfileSettingDescriptor
import org.helix.api.addon.ProfileSettingProvider
import org.slf4j.LoggerFactory

/**
 * Aggregates all [ProfileSettingProvider]s registered by addons, backing
 * the profile addon's interactive settings.
 *
 * A provider that throws is skipped for aggregation, so one broken addon
 * cannot break the whole profile view. The registry does not persist
 * setting values itself — the profile addon owns that — it only aggregates
 * descriptors and dispatches change notifications back to the owner that
 * registered a given setting.
 */
class ProfileSettingRegistry {
    private val logger = LoggerFactory.getLogger(ProfileSettingRegistry::class.java)
    private val providers = ConcurrentHashMap<String, CopyOnWriteArrayList<ProfileSettingProvider>>()

    /**
     * Registers a provider under an owner id.
     *
     * @param owner owning addon id, used for cleanup on disable and to
     *  route change notifications back to the right provider.
     * @param provider contributes settings for a player.
     */
    fun register(owner: String, provider: ProfileSettingProvider) {
        providers.computeIfAbsent(owner) { CopyOnWriteArrayList() }.add(provider)
    }

    /**
     * Removes all providers of an owner.
     *
     * @param owner the owning addon id.
     */
    fun unregisterOwner(owner: String) {
        providers.remove(owner)
    }

    /**
     * Owner id to setting descriptors, for every owner that contributes at
     * least one setting for this player.
     *
     * @param player player name.
     * @return owner id to that owner's setting descriptors.
     */
    fun settingsFor(player: String): Map<String, List<ProfileSettingDescriptor>> = buildMap {
        providers.forEach { (owner, ownerProviders) ->
            val descriptors = ownerProviders.flatMap { provider ->
                runCatching { provider.settingsFor(player) }
                    .onFailure { logger.error("profile-setting lookup failed for owner {}", owner, it) }
                    .getOrDefault(emptyList())
            }
            if (descriptors.isNotEmpty()) {
                put(owner, descriptors)
            }
        }
    }

    /**
     * Notifies every provider registered under [owner] that a value changed.
     *
     * @param owner the addon id that registered the changed setting.
     * @param player player name.
     * @param key the changed setting's key.
     * @param value the newly persisted value.
     */
    fun notifyChanged(owner: String, player: String, key: String, value: String) {
        providers[owner]?.forEach { provider ->
            runCatching { provider.onChanged(player, key, value) }
                .onFailure { logger.error("profile-setting change notification failed for owner {}", owner, it) }
        }
    }

    /**
     * Asks every provider registered under [owner] to validate a candidate
     * value, for checks a [org.helix.api.addon.ProfileSettingType] alone
     * cannot express.
     *
     * A throwing validator rejects the value (fail-closed) instead of
     * silently letting it through — validation exists to keep bad data
     * out, so a broken check must not default to "valid".
     *
     * @param owner the addon id that registered the setting.
     * @param player player name.
     * @param key the setting's key.
     * @param value the candidate value.
     * @return the first rejection reason, or `null` when every provider
     *  under [owner] accepts it (including when [owner] is unknown).
     */
    fun validate(owner: String, player: String, key: String, value: String): String? =
        providers[owner]?.firstNotNullOfOrNull { provider ->
            runCatching { provider.validate(player, key, value) }
                .getOrElse {
                    logger.error("profile-setting validation failed for owner {}", owner, it)
                    "validation error"
                }
        }
}
