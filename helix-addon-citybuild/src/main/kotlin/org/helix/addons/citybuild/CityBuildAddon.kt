package org.helix.addons.citybuild

import org.helix.addons.citybuild.config.ConfigSeeds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.helix.addon.sdk.AddonBase
import org.helix.addons.citybuild.config.ConfigDocument
import org.helix.addons.citybuild.config.ConfigStore
import org.helix.addons.citybuild.economy.ActionKronenGateway
import org.helix.addons.citybuild.economy.KronenGateway
import org.helix.addons.citybuild.service.ActionRegistrar
import org.helix.addons.citybuild.service.AdminShopService
import org.helix.addons.citybuild.service.Args
import org.helix.addons.citybuild.service.AuctionService
import org.helix.addons.citybuild.service.CityContext
import org.helix.addons.citybuild.service.CityException
import org.helix.addons.citybuild.service.CrateService
import org.helix.addons.citybuild.service.ForgeService
import org.helix.addons.citybuild.service.HomeService
import org.helix.addons.citybuild.service.JobService
import org.helix.addons.citybuild.service.KitService
import org.helix.addons.citybuild.service.OfferService
import org.helix.addons.citybuild.service.PlotMarketService
import org.helix.addons.citybuild.service.ProfileService
import org.helix.addons.citybuild.service.RewardService
import org.helix.addons.citybuild.service.UsageException
import org.helix.addons.citybuild.store.CityRepository
import org.helix.addons.citybuild.store.DocumentCityRepository
import org.helix.addons.citybuild.store.PostgresCityRepository
import org.helix.api.action.ActionResult
import org.helix.api.message.Messages
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.random.Random

/** Permission of the config write actions. */
private const val CONFIG_PERMISSION = "citybuild.admin.config"

/**
 * CityBuild („Kronstadt“) node addon: the single source of truth of the city instances
 * `CityBuild-1…3`.
 *
 * - Configuration (plots, merchant, jobs, kits, rewards, tiers, villagers, forge templates, crates,
 *   offers) in the config store, served to the Paper plugin as one versioned bundle.
 * - Per-instance state (homes, warps, kits, auctions, pickup boxes, plot market, offers) and
 *   network-wide player state (jobs, reward streak, tutorial, daily counters, serials, audit log) in
 *   PostgreSQL schema `citybuild`, or in the addon document store without a database.
 * - Kronen go through the economy addon ([KronenGateway]).
 *
 * Every `citybuild.*` action is bridge-invocable; the contract is documented in `ACTIONS.md`.
 *
 * @property gatewayFactory creates the Kronen gateway (tests inject a fake).
 * @property clock time source.
 * @property random randomness of crate draws.
 */
class CityBuildAddon(
    private val gatewayFactory: ((org.helix.api.action.ActionInvoker) -> KronenGateway)? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
) : AddonBase() {
    private val logger = LoggerFactory.getLogger(CityBuildAddon::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var messages: Messages
    private lateinit var config: ConfigStore
    private lateinit var repository: CityRepository
    private lateinit var vehicles: org.helix.addons.citybuild.vehicle.VehicleStore

    /** Opens the stores and registers all actions and providers. */
    override fun enable() {
        messages = loadMessages()
        config = ConfigStore(context.storage())
        repository = openRepository()
        vehicles = openVehicleStore()
        val kronen = gatewayFactory?.invoke(context.actions) ?: ActionKronenGateway(context.actions)
        val ctx = CityContext(repository, config, kronen, clock)
        val rewards = RewardService(ctx)
        val profiles = ProfileService(
            ctx,
            context.actions,
            resolveUuid = { name -> if (UUID_PATTERN.matches(name)) name else context.resolvePlayerUuid(name) },
            nameOf = { uuid -> context.lastKnownName(uuid) },
            label = { player, key -> messages.rawFor(player, key) },
        )
        val registrar = ActionRegistrar { name, usage, description, permission, handler ->
            // Only CityBuild servers may call CityBuild actions over the bridge (admin token: unrestricted).
            action(name, description, usage, permission = permission, bridgeInvocable = true, bridgeTasks = setOf(ConfigSeeds.TASK)) { inv ->
                handle(usage, inv.arguments, handler)
            }
        }
        registerConfigActions(registrar)
        HomeService(ctx).register(registrar)
        KitService(ctx).register(registrar)
        AuctionService(ctx).register(registrar)
        PlotMarketService(ctx).register(registrar)
        JobService(ctx, rewards).register(registrar)
        rewards.register(registrar)
        AdminShopService(ctx).register(registrar)
        ForgeService(ctx).register(registrar)
        CrateService(ctx, random).register(registrar)
        OfferService(ctx).register(registrar)
        org.helix.addons.citybuild.vehicle.VehicleService(ctx, vehicles).register(registrar)
        profiles.register(registrar)
        context.registerProfileInfoProvider(profiles.infoProvider)
        context.registerPlayerDataProvider(profiles.dataProvider)
        logger.info("CityBuild addon enabled: {} settings, collections {}", config.settings().size, config.collectionNames())
    }

    /** Closes the repository. */
    override fun onDisable() {
        if (::repository.isInitialized) repository.close()
        if (::vehicles.isInitialized) vehicles.close()
    }

    private fun handle(usage: String, arguments: List<String>, handler: (Args) -> ActionResult): ActionResult =
        try {
            handler(Args(arguments))
        } catch (e: UsageException) {
            ActionResult.error("usage", "${e.message}: $usage")
        } catch (e: CityException) {
            ActionResult.error(e.code, e.message ?: e.code)
        } catch (e: Exception) {
            logger.warn("CityBuild action failed ({}): {}", usage, e.toString())
            ActionResult.error("error", e.message ?: e.javaClass.simpleName)
        }

    private fun openRepository(): CityRepository {
        val connection = context.storageConnection()
        if (connection != null && connection.mode.equals("postgres", ignoreCase = true)) {
            return runCatching { PostgresCityRepository(connection) }
                .onFailure { logger.warn("PostgreSQL CityBuild repository unavailable, using document store: {}", it.message) }
                .getOrElse { DocumentCityRepository(context.storage()) }
        }
        return DocumentCityRepository(context.storage())
    }

    private fun openVehicleStore(): org.helix.addons.citybuild.vehicle.VehicleStore {
        val connection = context.storageConnection()
        if (connection != null && connection.mode.equals("postgres", ignoreCase = true)) {
            return runCatching { org.helix.addons.citybuild.vehicle.PostgresVehicleStore(connection) }
                .onFailure { logger.warn("PostgreSQL vehicle store unavailable, using document store: {}", it.message) }
                .getOrElse { org.helix.addons.citybuild.vehicle.DocumentVehicleStore(context.storage()) }
        }
        return org.helix.addons.citybuild.vehicle.DocumentVehicleStore(context.storage())
    }

    private fun registerConfigActions(r: ActionRegistrar) {
        r.register("citybuild.config.bundle", "citybuild.config.bundle", "The whole configuration as one JSON bundle.", null) { _ ->
            ActionResult.ok(config.bundleJson())
        }
        r.register("citybuild.config.version", "citybuild.config.version", "Current configuration version.", null) { _ ->
            ActionResult.ok(config.version().toString())
        }
        r.register("citybuild.config.export", "citybuild.config.export", "Settings with types and defaults as JSON.", null) { _ ->
            ActionResult.ok(config.settingsExportJson())
        }
        r.register("citybuild.config.set", "citybuild.config.set <key> <value…>", "Sets a setting (validated against its type).", CONFIG_PERMISSION) { a ->
            val key = a.str(0, "key")
            val value = runCatching { config.setSetting(key, a.values.drop(1).joinToString(" ")) }.getOrElse { throw CityException("invalid", it.message ?: "invalid value") }
            ActionResult.ok(json.encodeToString(mapOf(key to value)))
        }
        r.register("citybuild.config.reset", "citybuild.config.reset <key>", "Resets a setting to its default.", CONFIG_PERMISSION) { a ->
            val key = a.str(0, "key")
            val value = config.resetSetting(key) ?: throw CityException("not_found", "no default for '$key'")
            ActionResult.ok(json.encodeToString(mapOf(key to value)))
        }
        r.register("citybuild.config.collections", "citybuild.config.collections", "Collection names as JSON.", null) { _ ->
            ActionResult.ok(json.encodeToString(config.collectionNames()))
        }
        r.register("citybuild.config.docs", "citybuild.config.docs <collection>", "Documents of a collection as JSON.", null) { a ->
            ActionResult.ok(json.encodeToString(config.documents(a.str(0, "collection"))))
        }
        r.register("citybuild.config.doc.set", "citybuild.config.doc.set <collection> <id> <sortOrder> <json…>", "Creates or replaces a document.", CONFIG_PERMISSION) { a ->
            val body = a.json(3)
            config.putDocument(a.str(0, "collection"), ConfigDocument(a.str(1, "id"), a.int(2, "sortOrder"), body.jsonObject))
            ActionResult.ok(json.encodeToString(mapOf("version" to config.version())))
        }
        r.register("citybuild.config.doc.remove", "citybuild.config.doc.remove <collection> <id>", "Removes a document.", CONFIG_PERMISSION) { a ->
            if (!config.removeDocument(a.str(0, "collection"), a.str(1, "id"))) throw CityException("not_found", "no such document")
            ActionResult.ok(json.encodeToString(mapOf("version" to config.version())))
        }
        r.register("citybuild.config.seed", "citybuild.config.seed [force]", "Inserts missing defaults; 'force' overwrites everything.", CONFIG_PERMISSION) { a ->
            ActionResult.ok(json.encodeToString(mapOf("written" to config.seed(force = a.opt(0).equals("force", true)))))
        }
    }

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
