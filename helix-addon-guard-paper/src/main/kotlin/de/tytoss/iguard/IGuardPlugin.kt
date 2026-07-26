package de.tytoss.iguard

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerCommon
import de.tytoss.iguard.alert.AlertService
import de.tytoss.iguard.api.ExemptionManager
import de.tytoss.iguard.api.IGuardApi
import de.tytoss.iguard.api.IGuardApiImpl
import de.tytoss.iguard.check.CheckEngine
import de.tytoss.iguard.check.Enforcement
import de.tytoss.iguard.command.IGuardCommand
import de.tytoss.iguard.gui.GuiService
import de.tytoss.iguard.replay.ReplayService
import de.tytoss.iguard.spectate.SpectateService
import de.tytoss.iguard.config.DynamicConfig
import de.tytoss.iguard.config.IGuardConfig
import de.tytoss.iguard.packet.IGuardPacketListener
import de.tytoss.iguard.setback.SetbackService
import de.tytoss.iguard.snapshot.MainThreadSampler
import de.tytoss.iguard.snapshot.SnapshotStore
import de.tytoss.iguard.storage.GuardStore
import de.tytoss.iguard.storage.HelixNodeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.atomic.AtomicReference

/**
 * Plugin entry point: wires the packet listener, check engine, sampler, alerting, admin panel and the
 * Helix node persistence together. All durable state (violations, incidents, replays, bans) flows
 * through a [HelixNodeStore] — the plugin is only deployed as part of the Helix-Guard addon and never
 * talks to a database itself.
 */
class IGuardPlugin : JavaPlugin(), Listener {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private var storage: GuardStore? = null
    private var sampler: MainThreadSampler? = null
    private var alerts: AlertService? = null
    private var notifications: de.tytoss.iguard.notify.NotificationService? = null
    private var engine: CheckEngine? = null
    private var packetListener: IGuardPacketListener? = null
    private var registeredPacketListener: PacketListenerCommon? = null
    private var spectate: SpectateService? = null
    private var gui: GuiService? = null
    private var replay: ReplayService? = null
    private var banCoordinator: de.tytoss.iguard.ban.BanCoordinator? = null
    private var started = false

    /** Bootstraps all services; disables the plugin with a clear message when a requirement is missing. */
    override fun onEnable() {
        runCatching { initialize() }
            .onSuccess {
                started = true
                logger.info("IGuard enabled with PacketEvents 2.13.0 and Helix node history")
            }
            .onFailure { error ->
                logger.severe("IGuard cannot start: ${error.message}")
                server.pluginManager.disablePlugin(this)
            }
    }

    /** Stops listeners, workers and the store writer (flushing what it can) in dependency order. */
    override fun onDisable() {
        registeredPacketListener?.let { listener ->
            runCatching { PacketEvents.getAPI().eventManager.unregisterListener(listener) }
        }
        registeredPacketListener = null
        engine?.stopAccepting()
        engine?.shutdown()
        gui?.shutdown()
        spectate?.stopAll()
        replay?.stopAll()
        sampler?.stop()
        alerts?.stop()
        notifications?.shutdown()
        storage?.let { database ->
            runBlocking { database.stopAndFlush(3000) }
            database.close()
        }
        scope.cancel()
        if (started) logger.info("IGuard disabled")
        started = false
    }

    /** Drops the quitting player's per-connection packet state and any replay session they watched. */
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        packetListener?.remove(event.player.uniqueId)
        replay?.handleQuit(event.player.uniqueId)
    }

    /** Direct-join rejoin gate: deny login for players with an active network ban. Runs off-thread. */
    @EventHandler
    fun onPreLogin(event: org.bukkit.event.player.AsyncPlayerPreLoginEvent) {
        // The node owns network-wide enforcement, but this store-backed lookup keeps joins that bypass
        // the proxy gated as well.
        val ban = runCatching { storage?.activeBan(event.uniqueId) }.getOrNull() ?: return
        val until = ban.expiresAt?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "permanent"
        event.disallow(
            org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
            net.kyori.adventure.text.Component.text("IGuard: ${ban.reason}\nUntil: $until")
        )
    }

    private fun initialize() {
        require(Bukkit.getMinecraftVersion() == "1.21.11") { "Paper 1.21.11 is required, found ${Bukkit.getMinecraftVersion()}" }
        val packetPlugin = Bukkit.getPluginManager().getPlugin("packetevents")
            ?: Bukkit.getPluginManager().getPlugin("PacketEvents")
            ?: error("PacketEvents is not installed")
        require(packetPlugin.pluginMeta.version == "2.13.0") { "PacketEvents 2.13.0 is required, found ${packetPlugin.pluginMeta.version}" }
        require(PacketEvents.getAPI().isInitialized) { "PacketEvents is not initialized" }
        val loaded = IGuardConfig.load(this)
        val dynamic = AtomicReference(loaded.dynamic)
        val snapshots = SnapshotStore()
        val exemptions = ExemptionManager()
        // All persistence is a Helix-Cloud node action bridge (no database at all;
        // HELIX_CONTROL_URL/TOKEN come from the service environment and are validated in load()).
        val helixStore = HelixNodeStore(
            System.getenv("HELIX_CONTROL_URL").orEmpty(),
            System.getenv("HELIX_CONTROL_TOKEN").orEmpty(),
            loaded.history,
            logger
        )
        storage = helixStore
        logger.info("IGuard storage: helix node (${System.getenv("HELIX_CONTROL_URL")})")
        val alertService = AlertService(this, dynamic)
        alerts = alertService
        val notificationService = de.tytoss.iguard.notify.NotificationService(dynamic, logger)
        notifications = notificationService
        val setbackService = SetbackService(this, snapshots)
        // Ban enforcement is pluggable (config bans.provider): IGuard always writes its own audit +
        // notifications via the coordinator, then delegates the actual ban to the selected provider —
        // helix (the default; `native` is an alias) posts guard.store.ban/unban and the node kicks +
        // gates network-wide, command drives any ban plugin via console, service uses a provider
        // another plugin registered. Auto-enforcement, /iguard ban and the panel all funnel through it.
        val helixBanProvider = de.tytoss.iguard.ban.HelixBanProvider(helixStore)
        val commandBanProvider = de.tytoss.iguard.ban.CommandBanProvider(this, loaded.bans, logger)
        val banCoordinatorLocal = de.tytoss.iguard.ban.BanCoordinator(loaded.bans, helixBanProvider, commandBanProvider, helixStore, notificationService, logger)
        banCoordinator = banCoordinatorLocal
        logger.info("IGuard ban provider: ${loaded.bans.provider} (default backend: helix)")
        val enforcement = Enforcement { playerId, playerName, hours, reason, actor ->
            banCoordinatorLocal.ban(playerId, playerName, hours, reason, actor)
        }
        val checkEngine = CheckEngine(
            loaded.workers.stripes,
            loaded.workers.queueCapacity,
            snapshots,
            exemptions,
            loaded.exemptions,
            loaded.detection,
            loaded.sanctions,
            dynamic,
            loaded.serverId,
            helixStore,
            alertService,
            setbackService,
            enforcement,
            notificationService,
            logger
        )
        engine = checkEngine
        val packets = IGuardPacketListener(checkEngine)
        packetListener = packets
        val mainSampler = MainThreadSampler(this, snapshots, exemptions, loaded.exemptions, loaded.sampler)
        sampler = mainSampler
        val api = IGuardApiImpl(checkEngine, exemptions, banCoordinatorLocal)
        server.servicesManager.register(IGuardApi::class.java, api, this, ServicePriority.Normal)
        val spectateService = SpectateService(this)
        spectate = spectateService
        Bukkit.getPluginManager().registerEvents(spectateService, this)
        val replayService = ReplayService(this, helixStore, scope)
        replay = replayService
        val guiService = GuiService(this, checkEngine, exemptions, spectateService, replayService, banCoordinatorLocal, helixStore, loaded.serverId, scope)
        gui = guiService
        val commandHandler = IGuardCommand(this, loaded, dynamic, api, checkEngine, alertService, helixStore, mainSampler, spectateService, guiService, replayService, banCoordinatorLocal, scope)
        getCommand("iguard")?.apply {
            setExecutor(commandHandler)
            tabCompleter = commandHandler
        } ?: error("iguard command is missing from plugin.yml")
        Bukkit.getPluginManager().registerEvents(this, this)
        mainSampler.start()
        alertService.start()
        checkEngine.start()
        helixStore.start()
        guiService.install()
        registeredPacketListener = PacketEvents.getAPI().eventManager.registerListener(packets)
    }
}
