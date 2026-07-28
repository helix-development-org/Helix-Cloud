package org.helix.api.addon

import java.nio.file.Path
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionHandler
import org.helix.api.action.ActionInvoker
import org.helix.api.message.MapMessages
import org.helix.api.message.Messages
import org.helix.api.player.OnlinePlayer
import org.helix.api.storage.AddonStorage
import org.helix.api.storage.InMemoryAddonStorage

/**
 * Node facilities handed to an addon on enable.
 */
interface AddonContext {
    /** Directory the addon may persist data in, created before enable. */
    val dataDirectory: Path

    /**
     * Persistent document store for this addon.
     *
     * Backed by files or the shared PostgreSQL database depending on the
     * node's storage mode — the addon code is identical either way. Prefer
     * this over writing into [dataDirectory] directly.
     *
     * @return the addon-scoped storage.
     */
    fun storage(): AddonStorage = InMemoryAddonStorage()

    /** Invoker for calling any registered action from the addon. */
    val actions: ActionInvoker

    /**
     * Registers an action owned by this addon.
     *
     * Registered actions are removed again when the addon is disabled.
     *
     * @param descriptor name, description and usage of the action.
     * @param handler executed on invocation.
     */
    fun registerAction(descriptor: ActionDescriptor, handler: ActionHandler)

    /**
     * Registers a join gate owned by this addon.
     *
     * Proxy bridges ask the node on every login; the node evaluates all
     * registered gates. Gates are removed when the addon is disabled.
     *
     * @param gate evaluated on every join attempt.
     */
    fun registerJoinGate(gate: JoinGate)

    /**
     * Registers a player-data provider owned by this addon, backing GDPR
     * export/delete requests. Removed when the addon is disabled.
     *
     * @param provider exports and deletes this addon's data for a player.
     */
    fun registerPlayerDataProvider(provider: PlayerDataProvider) {
    }

    /**
     * Registers a permission resolver owned by this addon.
     *
     * Bridges and other addons ask the node for permissions; the node
     * grants when any resolver grants. Resolvers are removed when the
     * addon is disabled.
     *
     * @param resolver evaluated on every permission question.
     */
    fun registerPermissionResolver(resolver: PermissionResolver)

    /**
     * Asks the aggregated permission resolvers whether a player has a
     * permission.
     *
     * @param player player name.
     * @param permission permission node.
     * @return `true` when any registered resolver grants.
     */
    fun hasPermission(player: String, permission: String): Boolean = false

    /**
     * Lists all players currently connected to the network.
     *
     * @return online players sorted by name.
     */
    fun onlinePlayers(): List<OnlinePlayer> = emptyList()

    /**
     * Resolves the UUID a player name is known by on this network, even
     * while the player is offline, from the node's identity registry
     * (populated as players join).
     *
     * The primary defence against ban/permission evasion through renaming or
     * Mojang name recycling: identity-sensitive addons should key their
     * persisted data on this uuid instead of the name whenever it is known.
     * Also used to tell a real, previously-seen account name apart from an
     * arbitrary or misspelled one — for example to stop a nick from
     * impersonating a known premium account, or to reject `/pay` to a name
     * nobody has ever joined with.
     *
     * @param name player name, case-insensitive.
     * @return the uuid, or `null` when this node has never seen that name join.
     */
    fun resolvePlayerUuid(name: String): String? = null

    /**
     * The last-known name a uuid joined under, from the node's identity
     * registry.
     *
     * @param uuid player uuid.
     * @return the last-known name, or `null` when this node has never seen
     *  that uuid join.
     */
    fun lastKnownName(uuid: String): String? = null

    /**
     * Lists all installed addons with their manifests, including the
     * permission nodes each addon declares in its `addon.json`.
     *
     * @return snapshots of all installed addons.
     */
    fun installedAddons(): List<AddonInfo> = emptyList()

    /**
     * The permission nodes the platform itself implements (panel views,
     * panel login, maintenance bypass, player-command permissions).
     *
     * @return the node's own permission nodes.
     */
    fun corePermissions(): List<String> = emptyList()

    /**
     * Directories that may contain service files, for example backend
     * plugin jars in a `plugins` subdirectory — service workspaces and
     * templates. Used by addons that scan plugin metadata.
     *
     * @return existing directories to scan, may be empty.
     */
    fun serviceDirectories(): List<Path> = emptyList()

    /**
     * The node's central storage connection, for addon components that
     * reuse the node database (for example an anticheat persisting into
     * the same PostgreSQL). `null` when the node runs on file storage or
     * the connection is not exposed.
     *
     * @return the connection details, or `null`.
     */
    fun storageConnection(): StorageConnection? = null

    /**
     * Registers a network-wide player join/leave listener owned by this
     * addon. Removed when the addon is disabled.
     *
     * @param listener receives join and leave events.
     */
    fun registerPlayerListener(listener: PlayerListener) {
    }

    /**
     * Registers a display resolver owned by this addon. Removed when the
     * addon is disabled.
     *
     * @param resolver resolves chat/tab display profiles.
     */
    fun registerDisplayResolver(resolver: DisplayResolver) {
    }

    /**
     * Publishes a global bridge value, for example a tab list header.
     *
     * Bridges poll all published values; publishing the same key again
     * overwrites it. Values are removed when the addon is disabled.
     *
     * @param key value key, for example `tablist.header`.
     * @param value value text; `&` color codes are rendered by bridges.
     */
    fun publishBridgeValue(key: String, value: String) {
    }

    /**
     * Removes a previously published bridge value.
     *
     * A no-op if this addon does not currently own [key] — for example
     * because another addon has since published the same key.
     *
     * @param key value key to remove.
     */
    fun unpublishBridgeValue(key: String) {
    }

    /**
     * Publishes a notification to all registered listeners.
     *
     * Use this for events other addons may want to react to — bans,
     * warns, kicks and similar belong into the `moderation` category.
     *
     * @param category notification category, for example `moderation`.
     * @param message human readable text, `&` color codes allowed.
     */
    fun publishNotification(category: String, message: String) {
    }

    /**
     * Registers a dashboard page contributed by this addon. Removed when
     * the addon is disabled.
     *
     * @param panel the page to add to the dashboard sidebar.
     */
    fun registerDashboardPanel(panel: DashboardPanel) {
    }

    /**
     * Registers a notification listener owned by this addon. Removed when
     * the addon is disabled.
     *
     * @param listener receives all published notifications.
     */
    fun registerNotificationListener(listener: NotificationListener) {
    }

    /**
     * Whether this addon is active for the given task.
     *
     * Tasks can turn addons off individually; addons with per-service
     * behaviour should consult this for the task of the service/player they
     * act on. Network-wide addons may ignore it.
     *
     * @param taskName the task name.
     * @return `true` unless the addon is disabled for that task.
     */
    fun isActiveForTask(taskName: String): Boolean = true

    /**
     * Declares the addon's configurable message templates.
     *
     * The defaults seed `data/<addon>/messages.json` on first use (missing
     * keys are added, existing values kept). The returned [Messages] reads
     * the current values, so dashboard edits take effect immediately. Call
     * once on enable and keep the returned handle.
     *
     * @param defaults message key to default template, `{placeholder}`
     *   markers and `&` color codes allowed.
     * @return a live message accessor.
     */
    fun messages(defaults: Map<String, String>): Messages = MapMessages(defaults)

    /**
     * Declares the addon's configurable message templates in multiple
     * languages.
     *
     * Like [messages], but with one default map per language code. Players
     * receive the language they picked via `/helix language` (or their
     * Minecraft client language on first join) through
     * [Messages.formatFor]; missing translations fall back to the network's
     * default language.
     *
     * @param defaultsByLanguage language code to (message key to default
     *   template), `{placeholder}` markers and `&` color codes allowed.
     * @return a live message accessor.
     */
    fun localizedMessages(defaultsByLanguage: Map<String, Map<String, String>>): Messages =
        MapMessages(defaultsByLanguage["en"] ?: defaultsByLanguage.values.firstOrNull() ?: emptyMap())

    /**
     * Registers a read-only profile-info provider owned by this addon.
     * Removed when the addon is disabled.
     *
     * @param provider contributes display lines to a player's profile.
     */
    fun registerProfileInfoProvider(provider: ProfileInfoProvider) {
    }

    /**
     * Registers an interactive profile-setting provider owned by this
     * addon. Removed when the addon is disabled.
     *
     * @param provider contributes settings to a player's profile.
     */
    fun registerProfileSettingProvider(provider: ProfileSettingProvider) {
    }

    /**
     * Aggregates every registered [ProfileInfoProvider]'s lines for a
     * player, keyed by owning addon id — the profile addon reads this to
     * render a full profile without knowing which addons exist.
     *
     * @param player player name, matched case-insensitively.
     * @return owning addon id to that addon's display lines.
     */
    fun profileInfo(player: String): Map<String, List<ProfileInfoEntry>> = emptyMap()

    /**
     * Aggregates every registered [ProfileSettingProvider]'s settings for a
     * player, keyed by owning addon id.
     *
     * @param player player name, matched case-insensitively.
     * @return owning addon id to that addon's setting descriptors.
     */
    fun profileSettings(player: String): Map<String, List<ProfileSettingDescriptor>> = emptyMap()

    /**
     * Notifies the [ProfileSettingProvider] registered under [owner] that
     * the profile addon persisted a new value for one of its settings.
     *
     * Addons cannot call each other directly (each runs in its own
     * classloader), so a contributing addon that needs to react to a
     * changed value — for example re-rendering an equipped cosmetic —
     * receives it through this dispatch instead.
     *
     * @param owner the addon id that registered the changed setting.
     * @param player player name.
     * @param key the changed setting's key.
     * @param value the newly persisted value.
     */
    fun notifyProfileSettingChanged(owner: String, player: String, key: String, value: String) {
    }

    /**
     * Asks the [ProfileSettingProvider] registered under [owner] to
     * validate a candidate value before the profile addon persists it, for
     * checks a [ProfileSettingType] alone cannot express.
     *
     * @param owner the addon id that registered the setting.
     * @param player player name.
     * @param key the setting's key.
     * @param value the candidate value.
     * @return `null` when every registered provider under [owner] accepts
     *  it, or the first rejection reason.
     */
    fun validateProfileSetting(owner: String, player: String, key: String, value: String): String? = null
}
