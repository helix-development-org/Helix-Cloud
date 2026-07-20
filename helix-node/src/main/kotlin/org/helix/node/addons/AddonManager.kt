package org.helix.node.addons

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlinx.serialization.json.Json
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionHandler
import org.helix.api.action.ActionInvoker
import org.helix.api.addon.AddonContext
import org.helix.api.addon.AddonInfo
import org.helix.api.addon.AddonManifest
import org.helix.api.addon.AddonState
import org.helix.api.addon.DashboardPanel
import org.helix.api.addon.DisplayResolver
import org.helix.api.addon.HelixAddon
import org.helix.api.addon.JoinGate
import org.helix.api.addon.NotificationListener
import org.helix.api.addon.PermissionResolver
import org.helix.api.addon.PlayerListener
import org.helix.api.message.Messages
import org.helix.api.player.OnlinePlayer
import org.helix.api.proxy.PermissionCheckRequest
import org.helix.node.actions.ActionRegistry
import org.helix.node.dashboard.DashboardPanelRegistry
import org.helix.node.display.BridgeValueStore
import org.helix.node.display.DisplayResolverRegistry
import org.helix.node.gates.JoinGateRegistry
import org.helix.node.gates.PermissionResolverRegistry
import org.helix.node.messages.MessageBundle
import org.helix.node.messages.MessageRegistry
import org.helix.node.notifications.NotificationBus
import org.helix.node.players.PlayerRegistry
import org.slf4j.LoggerFactory

/**
 * Loads and manages HXA addons from `Helix/addons/`.
 *
 * An HXA file is a zip with an `addon.json` manifest and an `addon.jar`
 * containing the addon classes. Every addon gets its own classloader and a
 * scoped context; actions registered by an addon are removed again when it
 * is disabled.
 *
 * @property directory the `Helix/addons/` directory.
 * @property registry action registry addons register into.
 * @property joinGates join gate registry addons register into.
 * @property permissionResolvers permission registry addons register into.
 * @property playerRegistry online players and player event fan-out.
 * @property displayResolvers display profile registry addons register into.
 * @property bridgeValues global values bridges poll.
 * @property notifications notification bus between addons.
 * @property dashboardPanels dashboard pages contributed by addons.
 * @property messages configurable message bundles of addons.
 */
class AddonManager(
    private val directory: Path,
    private val registry: ActionRegistry,
    private val joinGates: JoinGateRegistry = JoinGateRegistry(),
    private val permissionResolvers: PermissionResolverRegistry = PermissionResolverRegistry(),
    private val playerRegistry: PlayerRegistry = PlayerRegistry(),
    private val displayResolvers: DisplayResolverRegistry = DisplayResolverRegistry(),
    private val bridgeValues: BridgeValueStore = BridgeValueStore(),
    private val notifications: NotificationBus = NotificationBus(),
    private val dashboardPanels: DashboardPanelRegistry = DashboardPanelRegistry(),
    private val messages: MessageRegistry = MessageRegistry(),
) {
    private val logger = LoggerFactory.getLogger(AddonManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val loaded = linkedMapOf<String, LoadedAddon>()

    private class LoadedAddon(
        val manifest: AddonManifest,
        val jar: Path,
    ) {
        var state: AddonState = AddonState.DISABLED
        var instance: HelixAddon? = null
        var classLoader: URLClassLoader? = null
        val actionNames = mutableSetOf<String>()
    }

    /**
     * Loads and enables every `.hxa` file in the addon directory.
     *
     * @return snapshots of all installed addons.
     */
    @Synchronized
    fun loadAll(): List<AddonInfo> {
        Files.createDirectories(directory)
        directory.listDirectoryEntries()
            .filter { it.extension == "hxa" }
            .sorted()
            .forEach { file ->
                runCatching { install(file) }
                    .onFailure { logger.error("Failed to load addon {}", file.fileName, it) }
            }
        return addons()
    }

    /**
     * Scans the addon directory and installs every `.hxa` that is not
     * loaded yet, without touching already-loaded addons.
     *
     * Lets operators drop new HXA files into `Helix/addons/` and pick them
     * up live via the `addon.list.reload` action — no node restart needed.
     * Malformed packages are logged and skipped.
     *
     * @return snapshots of the newly installed addons.
     */
    @Synchronized
    fun reload(): List<AddonInfo> {
        Files.createDirectories(directory)
        val added = mutableListOf<AddonInfo>()
        directory.listDirectoryEntries()
            .filter { it.extension == "hxa" }
            .sorted()
            .forEach { file ->
                runCatching {
                    val manifest = readManifest(file)
                    if (!loaded.containsKey(manifest.id)) {
                        added += install(file)
                    }
                }.onFailure { logger.error("Failed to load addon {}", file.fileName, it) }
            }
        return added
    }

    /**
     * Installs one HXA file and enables the addon.
     *
     * @param file path of the `.hxa` package.
     * @return snapshot of the installed addon.
     * @throws IllegalArgumentException if the package is malformed.
     */
    @Synchronized
    fun install(file: Path): AddonInfo {
        val manifest = readManifest(file)
        require(!loaded.containsKey(manifest.id)) { "addon already installed: ${manifest.id}" }
        val jarTarget = extractedJarPath(manifest)
        extractJar(file, jarTarget)
        val record = LoadedAddon(manifest, jarTarget)
        loaded[manifest.id] = record
        enableRecord(record)
        return info(record)
    }

    /**
     * Enables a disabled addon.
     *
     * @param id the addon id.
     * @return `true` if the addon exists and is enabled afterwards.
     */
    @Synchronized
    fun enable(id: String): Boolean {
        val record = loaded[id] ?: return false
        if (record.state == AddonState.ENABLED) {
            return true
        }
        enableRecord(record)
        return record.state == AddonState.ENABLED
    }

    /**
     * Disables an enabled addon and removes its actions.
     *
     * @param id the addon id.
     * @return `true` if the addon exists.
     */
    @Synchronized
    fun disable(id: String): Boolean {
        val record = loaded[id] ?: return false
        if (record.state != AddonState.ENABLED) {
            return true
        }
        runCatching { record.instance?.onDisable() }
            .onFailure { logger.warn("Addon {} failed during disable", id, it) }
        record.actionNames.forEach(registry::unregister)
        record.actionNames.clear()
        unregisterEverywhere(id)
        runCatching { record.classLoader?.close() }
        record.instance = null
        record.classLoader = null
        record.state = AddonState.DISABLED
        logger.info("Disabled addon {}", id)
        return true
    }

    /**
     * Disables all enabled addons, used on node shutdown.
     */
    @Synchronized
    fun disableAll() {
        loaded.keys.toList().forEach(::disable)
    }

    /**
     * Lists all installed addons.
     *
     * @return snapshots sorted by addon id.
     */
    @Synchronized
    fun addons(): List<AddonInfo> = loaded.values.map(::info).sortedBy { it.manifest.id }

    private fun enableRecord(record: LoadedAddon) {
        runCatching {
            val classLoader = URLClassLoader(
                arrayOf(record.jar.toUri().toURL()),
                javaClass.classLoader,
            )
            val instance = classLoader.loadClass(record.manifest.main)
                .getDeclaredConstructor()
                .newInstance() as HelixAddon
            record.classLoader = classLoader
            record.instance = instance
            instance.onEnable(ScopedContext(record))
            record.state = AddonState.ENABLED
            logger.info("Enabled addon {} {}", record.manifest.id, record.manifest.version)
        }.onFailure { failure ->
            record.state = AddonState.FAILED
            record.actionNames.forEach(registry::unregister)
            record.actionNames.clear()
            unregisterEverywhere(record.manifest.id)
            logger.error("Enabling addon {} failed", record.manifest.id, failure)
        }
    }

    private fun readManifest(file: Path): AddonManifest = ZipFile(file.toFile()).use { zip ->
        val entry = requireNotNull(zip.getEntry("addon.json")) { "$file misses addon.json" }
        json.decodeFromString<AddonManifest>(zip.getInputStream(entry).readAllBytes().decodeToString())
    }

    private fun extractJar(file: Path, target: Path) {
        ZipFile(file.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("addon.jar")) { "$file misses addon.jar" }
            Files.createDirectories(target.parent)
            zip.getInputStream(entry).use { stream ->
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun unregisterEverywhere(id: String) {
        joinGates.unregisterOwner(id)
        permissionResolvers.unregisterOwner(id)
        playerRegistry.unregisterOwner(id)
        displayResolvers.unregisterOwner(id)
        bridgeValues.unpublishOwner(id)
        notifications.unregisterOwner(id)
        dashboardPanels.unregisterOwner(id)
        messages.unregisterOwner(id)
    }

    private fun extractedJarPath(manifest: AddonManifest): Path =
        directory.resolve(".extracted/${manifest.id}-${manifest.version}.jar")

    private fun info(record: LoadedAddon): AddonInfo = AddonInfo(record.manifest, record.state)

    private inner class ScopedContext(private val record: LoadedAddon) : AddonContext {
        override val dataDirectory: Path =
            Files.createDirectories(directory.resolve("data/${record.manifest.id}"))

        override val actions: ActionInvoker
            get() = registry

        override fun registerAction(descriptor: ActionDescriptor, handler: ActionHandler) {
            registry.register(descriptor, handler)
            record.actionNames += descriptor.name
        }

        override fun registerJoinGate(gate: JoinGate) {
            joinGates.register(record.manifest.id, gate)
        }

        override fun registerPermissionResolver(resolver: PermissionResolver) {
            permissionResolvers.register(record.manifest.id, resolver)
        }

        override fun hasPermission(player: String, permission: String): Boolean =
            permissionResolvers.evaluate(PermissionCheckRequest(player, permission))

        override fun onlinePlayers(): List<OnlinePlayer> = playerRegistry.online()

        override fun registerPlayerListener(listener: PlayerListener) {
            playerRegistry.register(record.manifest.id, listener)
        }

        override fun registerDisplayResolver(resolver: DisplayResolver) {
            displayResolvers.register(record.manifest.id, resolver)
        }

        override fun publishBridgeValue(key: String, value: String) {
            bridgeValues.publish(record.manifest.id, key, value)
        }

        override fun publishNotification(category: String, message: String) {
            notifications.publish(category, message)
        }

        override fun registerDashboardPanel(panel: DashboardPanel) {
            dashboardPanels.register(record.manifest.id, panel)
        }

        override fun messages(defaults: Map<String, String>): Messages {
            val bundle = MessageBundle(dataDirectory.resolve("messages.json"), defaults)
            messages.register(record.manifest.id, bundle)
            return bundle
        }

        override fun registerNotificationListener(listener: NotificationListener) {
            notifications.register(record.manifest.id, listener)
        }
    }
}
