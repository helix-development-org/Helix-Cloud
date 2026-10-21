package org.helix.addons.guardlab.paper

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Horse
import org.bukkit.entity.Husk
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * A client that answered `hello` with `ready`.
 *
 * @property playerId player UUID.
 * @property name player name.
 * @property version client version.
 * @property brand client brand.
 */
data class ClientInfo(val playerId: UUID, val name: String, val version: String, val brand: String)

/**
 * Runner tuning from `config.yml`.
 *
 * @property cooldownTicks minimum pause between scenarios.
 * @property maxCooldownTicks longest wait for violation levels to decay.
 * @property quitAfterRun sends `quit` to the clients after a run.
 */
data class RunnerSettings(val cooldownTicks: Int = 60, val maxCooldownTicks: Int = 400, val quitAfterRun: Boolean = true)

/**
 * Snapshot of the running suite for the live sidebar ([LiveSidebar]).
 *
 * @property suite suite id.
 * @property client the run's client.
 * @property done scenarios with a result.
 * @property total scenarios of the suite.
 * @property counts results so far by status.
 * @property scenario id of the scenario in progress, null during the pause between scenarios.
 * @property cheat cheat mode of that scenario (`none` for legit ones).
 * @property cheatOn the cheat is switched on right now.
 * @property lag lag profile of that scenario (`none` without lag).
 * @property phase readable phase (`Aufbau`, `Start`, `läuft`, `Nachlauf`, `Pause`).
 * @property playTick playback tick of the scenario.
 * @property durationTicks planned playback length.
 * @property setbacks IGuard setbacks during this scenario.
 */
data class LiveRun(
    val suite: String,
    val client: UUID,
    val done: Int,
    val total: Int,
    val counts: Map<Status, Int>,
    val scenario: String?,
    val cheat: String,
    val cheatOn: Boolean,
    val lag: String,
    val phase: String,
    val playTick: Int,
    val durationTicks: Int,
    val setbacks: Int,
)

/**
 * Runs a suite scenario by scenario against one GuardLab client.
 *
 * Per scenario: start IGuard's trace (`<suite>-<scenario>`) → prepare the player (spot, game mode,
 * effects, items, vehicle, target mob, dummy, lag profile) → wait `setupTicks` (teleport exemptions
 * expire) → send `scenario` → collect IGuard's frame reports ([FrameStats]: failed/counted per check,
 * exemptions, tags) and sample every tick into GuardLab's trace until `finished` (+ a short tail for late
 * flags) → stop IGuard's trace and copy it next to GuardLab's → judge → clean up → cool down until the
 * levels decayed → next. Phase changes (setup, playback, cheat on/off, lag spikes, setbacks …) are marked
 * in both traces. Setbacks are IGuard's plugin teleports during playback. Everything runs on the main
 * thread except the frame listener (IGuard's worker threads), Toxiproxy calls and trace copies.
 *
 * @property plugin the plugin.
 * @property lab the lab world.
 * @property probe IGuard access.
 * @property toxiproxy lag control, null when not configured.
 * @property settings tuning.
 * @property reportsDir `plugins/GuardLab/reports`.
 * @property service this service id (report header).
 * @property dummy the connected dummy client, if any.
 * @property onReport called after a run with the report and its directory name.
 */
class LabRunner(
    private val plugin: JavaPlugin,
    private val lab: LabWorld,
    private val probe: GuardProbe,
    private val toxiproxy: ToxiproxyClient?,
    private val settings: RunnerSettings,
    private val reportsDir: File,
    private val service: String,
    private val dummy: () -> Player?,
    private val onReport: (RunReport, String) -> Unit,
) {
    private enum class Phase { SETUP, WAIT_START, PLAYING, TAIL }

    private class Active(val scenario: Scenario, val index: Int) {
        val tracker = ViolationTracker()
        val startedAt: Instant = Instant.now()
        var trace: TraceWriter? = null
        var traceName: String? = null
        var iguard: ProbeTrace? = null
        var iguardName: String? = null
        var frames: FrameStats? = null
        var cheatOn = false
        var phase = Phase.SETUP
        var phaseTicks = 0
        var playTick = 0
        var setbacks = 0
        var finished = false
        var clientTicks = 0
        var error: String? = null
        var skipped: String? = null
        var spiking = false
        val pings = mutableListOf<Int>()
        val entities = mutableListOf<Entity>()
        val notes = mutableListOf<String>()

        /** null = pending, "" = applied, otherwise the Toxiproxy problem. */
        val lag = AtomicReference<String?>(null)
    }

    private class Run(
        val suite: String,
        val scenarios: List<Scenario>,
        val catalog: Map<String, Scenario>,
        val client: ClientInfo,
        val dirName: String,
        val startedAt: Instant,
        val dummy: Boolean,
        val toxiproxy: String,
    ) {
        val results = mutableListOf<ScenarioResult>()
        var active: Active? = null
        var next = 0
        var cooldownTicks = 0
        var cooldownTarget = 0
        var lastLevelSum = Double.MAX_VALUE
        var stableTicks = 0
        var task: BukkitTask? = null
    }

    private var run: Run? = null
    private var selfTeleport = false
    private var frameListener: AutoCloseable? = null

    /** Collector of the scenario in playback; written by IGuard's worker threads through [frameListener]. */
    @Volatile private var frameStats: FrameStats? = null

    /** Whether a run is in progress. */
    val running: Boolean get() = run != null

    /** The client of the current run. */
    val currentClient: UUID? get() = run?.client?.playerId

    /**
     * Human-readable progress.
     *
     * @return status lines.
     */
    fun status(): List<String> {
        val run = run ?: return listOf("kein Lauf aktiv")
        val active = run.active
        return listOf(
            "Suite ${run.suite} für ${run.client.name}: ${run.results.size}/${run.scenarios.size} fertig",
            active?.let { "aktuell: ${it.scenario.id} (${it.phase.name.lowercase()}, Tick ${it.playTick})" } ?: "Pause zwischen Szenarien",
            "bisher: " + Status.entries.joinToString { s -> "${s.name.lowercase()} ${run.results.count { it.status == s }}" },
        )
    }

    /**
     * The running suite for the live sidebar.
     *
     * @return the snapshot, null when no run is in progress.
     */
    fun live(): LiveRun? {
        val run = run ?: return null
        val active = run.active
        return LiveRun(
            suite = run.suite,
            client = run.client.playerId,
            done = run.results.size,
            total = run.scenarios.size,
            counts = run.results.groupingBy { it.status }.eachCount(),
            scenario = active?.scenario?.id,
            cheat = active?.scenario?.cheatMode ?: "none",
            cheatOn = active?.cheatOn == true,
            lag = active?.scenario?.lag ?: "none",
            phase = when (active?.phase) {
                null -> "Pause"
                Phase.SETUP -> "Aufbau"
                Phase.WAIT_START -> "Start"
                Phase.PLAYING -> "läuft"
                Phase.TAIL -> "Nachlauf"
            },
            playTick = active?.playTick ?: 0,
            durationTicks = active?.scenario?.durationTicks ?: 0,
            setbacks = active?.setbacks ?: 0,
        )
    }

    /**
     * Starts a suite.
     *
     * @param suite suite id.
     * @param scenarios the suite's scenarios.
     * @param catalog every scenario (coverage).
     * @param client the ready client.
     * @param toxiproxyStatus `ok` or the Toxiproxy problem (checked async beforehand).
     * @return null when started, otherwise why not.
     */
    fun start(suite: String, scenarios: List<Scenario>, catalog: Map<String, Scenario>, client: ClientInfo, toxiproxyStatus: String): String? {
        if (run != null) return "es läuft schon ein Lauf"
        if (!lab.ready) return "Testwelt wird noch gebaut"
        val player = plugin.server.getPlayer(client.playerId) ?: return "Client ist nicht online"
        if (player.hasPermission(BYPASS)) return "${client.name} hat $BYPASS – IGuard prüft ihn nicht (nicht mit dem Owner-Account testen)"
        if (probe.sanctionsMode == "enforce") return "IGuard-Sanktionen stehen auf enforce – der Test-Account würde gebannt"
        val now = Instant.now()
        val dirName = DIR_FORMAT.format(now) + "-" + suite
        val started = Run(suite, scenarios, catalog, client, dirName, now, dummy() != null, toxiproxyStatus)
        run = started
        frameListener = runCatching { probe.onFrames { frame -> frameStats?.record(frame) } }
            .onFailure { plugin.logger.warning("GuardLab: IGuard-Frame-Berichte nicht verfügbar: ${it.message}") }
            .getOrNull()
        if (frameListener == null) plugin.logger.warning("GuardLab: keine IGuard-Frame-Berichte – Auswertung über VL-Anstiege")
        plugin.logger.info("GuardLab: starte Suite $suite (${scenarios.size} Szenarien) für ${client.name}")
        started.task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
        return null
    }

    /**
     * Writes an aborted report without running anything (refused start), so waiting tools see an outcome.
     *
     * @param suite suite id.
     * @param scenarios scenarios that would have run.
     * @param catalog every scenario.
     * @param client the client.
     * @param reason why.
     */
    fun refuse(suite: String, scenarios: List<Scenario>, catalog: Map<String, Scenario>, client: ClientInfo, reason: String) {
        val now = Instant.now()
        val refused = Run(suite, scenarios, catalog, client, DIR_FORMAT.format(now) + "-" + suite, now, dummy() != null, "-")
        scenarios.forEachIndexed { index, scenario -> refused.results += skippedResult(scenario, index, "nicht gestartet: $reason") }
        writeReport(refused, reason)
    }

    /**
     * Stops the run; the current and remaining scenarios are skipped.
     *
     * @param reason why.
     */
    fun abort(reason: String) {
        val current = run ?: return
        current.active?.let { active ->
            active.skipped = "abgebrochen: $reason"
            complete(plugin.server.getPlayer(current.client.playerId), active)
        }
        while (current.next < current.scenarios.size) {
            val index = current.next++
            current.results += skippedResult(current.scenarios[index], index, "abgebrochen: $reason")
        }
        plugin.server.getPlayer(current.client.playerId)?.let { send(it, Protocol.abort(reason)) }
        finish(current, reason)
    }

    /**
     * Handles a message of the run's client.
     *
     * @param player sender.
     * @param message the message.
     */
    fun onClientMessage(player: Player, message: ClientMessage) {
        val current = run ?: return
        if (player.uniqueId != current.client.playerId) return
        val active = current.active ?: return
        when (message) {
            is ClientMessage.Started -> if (message.id == active.scenario.id && active.phase == Phase.WAIT_START) {
                active.phase = Phase.PLAYING
                active.phaseTicks = 0
                active.playTick = 0
                mark(active, "started")
                switchCheat(active)
            }
            is ClientMessage.Finished -> if (message.id == active.scenario.id && active.phase in PLAYBACK) {
                active.finished = true
                active.clientTicks = message.ticks
                if (active.cheatOn) mark(active, "cheat_off")
                active.cheatOn = false
                mark(active, "finished", "${message.ticks} ticks")
                toTail(active)
            }
            is ClientMessage.Error -> if ((message.id.isEmpty() || message.id == active.scenario.id) && active.phase != Phase.TAIL) {
                active.error = message.reason.ifBlank { "unbekannter Fehler" }
                mark(active, "error", active.error.orEmpty())
                toTail(active)
            }
            is ClientMessage.Ready -> Unit
        }
    }

    /**
     * Counts IGuard setbacks (plugin teleports the runner did not issue) during playback.
     *
     * @param event the teleport.
     */
    fun onTeleport(event: PlayerTeleportEvent) {
        if (selfTeleport || event.cause != PlayerTeleportEvent.TeleportCause.PLUGIN) return
        val current = run ?: return
        if (event.player.uniqueId != current.client.playerId) return
        val active = current.active ?: return
        if (active.phase == Phase.SETUP) return
        active.setbacks++
        val to = event.to
        mark(active, "setback", "%.2f %.2f %.2f".format(to.x, to.y, to.z))
    }

    /**
     * Notes a death of the run's client.
     *
     * @param player the player who died.
     */
    fun onDeath(player: Player) {
        val current = run ?: return
        if (player.uniqueId != current.client.playerId) return
        val active = current.active ?: return
        active.notes += "Spieler ist gestorben (Tick ${active.playTick}) – Szenario-Geometrie prüfen"
        mark(active, "death")
    }

    /** Where a dying client respawns: the waiting spot of the lab. */
    fun respawnLocation() = Stations.resolve(WAIT_SPOT)?.let { lab.location(it) }

    // ---------------------------------------------------------------- tick loop

    private fun tick() {
        val current = run ?: return
        val player = plugin.server.getPlayer(current.client.playerId)
        if (player == null) {
            abort("Client getrennt")
            return
        }
        val active = current.active
        if (active == null) {
            current.cooldownTicks++
            if (current.cooldownTicks >= current.cooldownTarget && settled(current, player)) startNext(current, player)
            return
        }
        active.phaseTicks++
        when (active.phase) {
            Phase.SETUP -> setupTick(current, player, active)
            Phase.WAIT_START, Phase.PLAYING -> playTick(player, active)
            Phase.TAIL -> {
                sample(player, active)
                val tail = if (active.scenario.lag == "none") TAIL_TICKS else LAG_TAIL_TICKS
                if (active.error != null || active.phaseTicks >= tail) complete(player, active)
            }
        }
    }

    /**
     * Whether IGuard's levels have decayed enough for the next scenario: all below [DECAYED], or no longer
     * falling for [SETTLE_TICKS] (checks without decay, e.g. the client identity), or the cooldown cap.
     */
    private fun settled(current: Run, player: Player): Boolean {
        if (current.cooldownTarget == 0 || current.cooldownTicks >= settings.maxCooldownTicks) return true
        val levels = probe.levels(player.uniqueId).values
        if ((levels.maxOrNull() ?: 0.0) < DECAYED) return true
        val sum = levels.sum()
        current.stableTicks = if (sum < current.lastLevelSum - 1e-3) 0 else current.stableTicks + 1
        current.lastLevelSum = sum
        return current.stableTicks >= SETTLE_TICKS
    }

    private fun startNext(current: Run, player: Player) {
        if (current.next >= current.scenarios.size) {
            finish(current, null)
            return
        }
        val index = current.next++
        val scenario = current.scenarios[index]
        val skip = when {
            scenario.setup.dummy != null && dummy() == null -> "kein Dummy-Client verbunden (Spieler-Ziel nötig)"
            scenario.lag != "none" && toxiproxy == null -> "Toxiproxy nicht konfiguriert"
            scenario.lag != "none" && current.toxiproxy != "ok" -> "Toxiproxy: ${current.toxiproxy}"
            else -> null
        }
        if (skip != null) {
            current.results += skippedResult(scenario, index, skip)
            current.cooldownTicks = 0
            current.cooldownTarget = 0
            return
        }
        val active = Active(scenario, index)
        current.active = active
        prepare(player, active)
    }

    private fun setupTick(current: Run, player: Player, active: Active) {
        if (active.skipped != null) {
            complete(player, active)
            return
        }
        val setup = active.scenario.setup
        healMobs(active)
        if (active.phaseTicks == MOUNT_TICK && setup.vehicle != null) mount(player, active)
        if (active.phaseTicks < active.scenario.setupTicks) return
        val lagState = active.lag.get()
        if (lagState == null) {
            if (active.phaseTicks < active.scenario.setupTicks + LAG_WAIT_TICKS) return
            active.skipped = "Toxiproxy antwortet nicht rechtzeitig"
            complete(player, active)
            return
        }
        if (lagState.isNotEmpty() && active.scenario.lag != "none") {
            active.skipped = "Toxiproxy: $lagState"
            complete(player, active)
            return
        }
        // Playback: effects (timed ones like levitation must not run out during setup), baseline, frame
        // collector, trace, message.
        applyEffects(player, active)
        val baseline = probe.levels(player.uniqueId)
        active.tracker.start(baseline)
        active.frames = FrameStats(player.uniqueId, baseline).also { frameStats = it }
        active.traceName = "traces/%03d_%s.jsonl".format(active.index + 1, active.scenario.id)
        active.trace = runCatching { TraceWriter(File(reportsDir, current.dirName + "/" + active.traceName)) }
            .onFailure { plugin.logger.warning("GuardLab: Trace nicht schreibbar: ${it.message}") }
            .getOrNull()
        mark(active, "scenario", "${active.scenario.id} cheat=${active.scenario.cheatMode} lag=${active.scenario.lag}", 0)
        val scenario = active.scenario
        send(player, Protocol.scenario(scenario.id, active.index, scenario.setupTicks, scenario.script, scenario.cheat, scenario.durationTicks))
        if (setup.dummy != null && scenario.dummyScript.isNotEmpty()) {
            dummy()?.let { send(it, Protocol.scenario(scenario.id + ".dummy", active.index, scenario.setupTicks, scenario.dummyScript, NO_CHEAT, scenario.durationTicks)) }
        }
        active.phase = Phase.WAIT_START
        active.phaseTicks = 0
    }

    private fun playTick(player: Player, active: Active) {
        sample(player, active)
        healMobs(active)
        if (active.phase == Phase.WAIT_START) {
            if (active.phaseTicks > START_TIMEOUT_TICKS) {
                active.error = "Client hat das Szenario nicht gestartet (kein started nach ${START_TIMEOUT_TICKS / 20} s)"
                toTail(active)
            }
            return
        }
        active.playTick++
        switchCheat(active)
        mechanics(player, active)
        if (active.playTick > active.scenario.durationTicks + PLAY_GRACE_TICKS) {
            mark(active, "timeout")
            toTail(active)
        }
    }

    /** Marks the moment the client's cheat switches on (`params.startTick`, approximated in server ticks). */
    private fun switchCheat(active: Active) {
        val scenario = active.scenario
        if (active.cheatOn || scenario.cheatMode == "none" || active.playTick < scenario.cheatStartTick) return
        active.cheatOn = true
        mark(active, "cheat_on", scenario.cheatMode + if (scenario.cheatStartTick > 0) " ab Client-Tick ${scenario.cheatStartTick}" else "")
    }

    /**
     * Writes a marker into GuardLab's trace and IGuard's recording (`guardlab:<marker> t=<tick> <detail>`).
     *
     * @param active the scenario.
     * @param marker marker name.
     * @param detail optional detail.
     * @param tick playback tick (default: the current one).
     */
    private fun mark(active: Active, marker: String, detail: String = "", tick: Int = active.playTick) {
        active.trace?.let { trace -> runCatching { trace.marker(tick, marker, detail) } }
        active.iguard?.let { trace -> runCatching { trace.marker("guardlab:$marker t=$tick" + if (detail.isEmpty()) "" else " $detail") } }
    }

    private fun toTail(active: Active) {
        active.phase = Phase.TAIL
        active.phaseTicks = 0
    }

    private fun sample(player: Player, active: Active) {
        val rises = active.tracker.sample(probe.levels(player.uniqueId))
        if (active.phase == Phase.PLAYING) active.pings += player.ping
        val trace = active.trace ?: return
        val location = player.location
        val velocity = player.velocity
        @Suppress("DEPRECATION")
        val onGround = player.isOnGround
        runCatching {
            trace.sample(
                TraceSample(
                    active.playTick, location.x, location.y, location.z, velocity.x, velocity.y, velocity.z, onGround,
                    location.yaw, location.pitch, player.ping, plugin.server.tps.firstOrNull() ?: 20.0, rises,
                ),
            )
        }
    }

    private fun mechanics(player: Player, active: Active) {
        val setup = active.scenario.setup
        val tick = active.playTick
        setup.knockback?.let { kb ->
            if (tick % kb.everyTicks.coerceAtLeast(1) == 0) {
                val facing = player.location.direction.setY(0)
                val horizontal = if (facing.lengthSquared() < 1e-6) Vector(0, 0, 1) else facing.normalize()
                player.velocity = horizontal.multiply(-kb.strength).setY(kb.upward)
                mark(active, "knockback")
            }
        }
        setup.pistonPulseTicks?.let { every ->
            val points = Stations.pulses(setup.spot.substringBefore('.'))
            when (tick % every.coerceAtLeast(PULSE_LENGTH + 1)) {
                0 -> {
                    points.forEach { (x, y, z) -> lab.setWithPhysics(x, y, z, REDSTONE_BLOCK) }
                    mark(active, "pulse")
                }
                PULSE_LENGTH -> points.forEach { (x, y, z) -> lab.setWithPhysics(x, y, z, AIR) }
            }
        }
        if (active.scenario.lag == "spike" && toxiproxy != null) {
            val phase = tick % LagProfiles.SPIKE_EVERY_TICKS
            val spiking = phase in LagProfiles.SPIKE_EVERY_TICKS - LagProfiles.SPIKE_TICKS until LagProfiles.SPIKE_EVERY_TICKS
            if (spiking != active.spiking) {
                active.spiking = spiking
                mark(active, if (spiking) "spike" else "spike_end")
                async { toxiproxy.apply("spike", spiking) }
            }
        }
    }

    private fun healMobs(active: Active) {
        active.entities.filterIsInstance<Husk>().forEach { mob ->
            if (mob.isValid) mob.health = mob.getAttribute(Attribute.MAX_HEALTH)?.value ?: mob.health
        }
    }

    // ---------------------------------------------------------------- scenario setup / cleanup

    private fun prepare(player: Player, active: Active) {
        val setup = active.scenario.setup
        startIGuardTrace(player, active)
        mark(active, "setup", "spot=${setup.spot} gamemode=${setup.gamemode}" + (setup.vehicle?.let { " vehicle=$it" } ?: ""), 0)
        resetPlayer(player)
        player.gameMode = when (setup.gamemode) {
            "creative" -> GameMode.CREATIVE
            "adventure" -> GameMode.ADVENTURE
            else -> GameMode.SURVIVAL
        }
        val spot = Stations.resolve(setup.spot)
        if (spot == null) {
            active.skipped = "unbekannter Spot ${setup.spot}"
            return
        }
        teleport(player, spot)
        player.allowFlight = setup.allowFlight
        if (setup.allowFlight && setup.flying) player.isFlying = true
        setup.items.forEach { spec -> giveItem(player, spec, active) }
        setup.mob?.let { ref -> Stations.resolve(ref)?.let { spawnTarget(it, active) } }
        setup.dummy?.let { ref ->
            val target = dummy()
            val pos = Stations.resolve(ref)
            if (target != null && pos != null) {
                resetPlayer(target)
                target.gameMode = GameMode.SURVIVAL
                teleport(target, pos)
            }
        }
        val client = toxiproxy
        if (client == null) {
            active.lag.set("")
        } else {
            val profile = active.scenario.lag
            async { active.lag.set(client.apply(profile).orEmpty()) }
        }
    }

    private fun applyEffects(player: Player, active: Active) {
        active.scenario.setup.effects.forEach { effect ->
            val type = Registry.EFFECT.get(NamespacedKey.minecraft(effect.type))
            if (type == null) active.notes += "unbekannter Effekt ${effect.type}" else {
                player.addPotionEffect(PotionEffect(type, effect.seconds * 20, effect.amplifier, false, false))
            }
        }
    }

    private fun giveItem(player: Player, spec: ItemSpec, active: Active) {
        val material = Material.matchMaterial(spec.material)
        if (material == null) {
            active.notes += "unbekanntes Material ${spec.material}"
            return
        }
        val item = ItemStack(material, spec.amount.coerceIn(1, material.maxStackSize))
        spec.enchants.forEach { (key, level) ->
            Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key))?.let { item.addUnsafeEnchantment(it, level) }
                ?: run { active.notes += "unbekannte Verzauberung $key" }
        }
        val inventory = player.inventory
        when (spec.slot) {
            "offhand" -> inventory.setItemInOffHand(item)
            "head" -> inventory.helmet = item
            "chest" -> inventory.chestplate = item
            "legs" -> inventory.leggings = item
            "feet" -> inventory.boots = item
            else -> {
                val slot = spec.slot.removePrefix("hotbar").toIntOrNull()
                if (slot == null || slot !in 0..8) active.notes += "unbekannter Slot ${spec.slot}" else inventory.setItem(slot, item)
            }
        }
    }

    private fun spawnTarget(pos: LabPos, active: Active) {
        val mob = lab.world.spawn(lab.location(pos), Husk::class.java, false) { husk ->
            husk.setAI(false)
            husk.isSilent = true
            husk.isPersistent = true
            husk.removeWhenFarAway = false
            husk.setAdult()
            husk.getAttribute(Attribute.MAX_HEALTH)?.baseValue = TARGET_HEALTH
            husk.health = TARGET_HEALTH
        }
        active.entities += mob
    }

    private fun mount(player: Player, active: Active) {
        val location = player.location
        val world = lab.world
        val vehicle: Entity = when (active.scenario.setup.vehicle) {
            "boat" -> world.spawnEntity(location, EntityType.OAK_BOAT)
            "minecart" -> world.spawnEntity(location, EntityType.MINECART)
            "horse" -> world.spawn(location, Horse::class.java, false) { horse ->
                horse.isTamed = true
                horse.owner = player
                horse.setAdult()
                horse.inventory.saddle = ItemStack(Material.SADDLE)
                horse.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = HORSE_SPEED
                horse.jumpStrength = HORSE_JUMP
            }
            "seat" -> world.spawn(location.clone().add(0.0, SEAT_OFFSET, 0.0), ArmorStand::class.java, false) { stand ->
                stand.isVisible = false
                stand.setGravity(false)
                stand.isInvulnerable = true
                stand.isSmall = true
                stand.isMarker = true
            }
            else -> return
        }
        active.entities += vehicle
        if (!vehicle.addPassenger(player)) active.notes += "Aufsteigen auf ${active.scenario.setup.vehicle} fehlgeschlagen"
        mark(active, "mount", active.scenario.setup.vehicle.orEmpty(), 0)
    }

    private fun cleanup(player: Player?, active: Active) {
        val setup = active.scenario.setup
        player?.leaveVehicle()
        active.entities.forEach { if (it.isValid) it.remove() }
        active.entities.clear()
        setup.pistonPulseTicks?.let { Stations.pulses(setup.spot.substringBefore('.')).forEach { (x, y, z) -> lab.setWithPhysics(x, y, z, AIR) } }
        if (player != null) {
            resetPlayer(player)
            Stations.resolve(WAIT_SPOT)?.let { teleport(player, it) }
        }
        if (setup.dummy != null) dummy()?.let { d -> Stations.resolve(DUMMY_SPOT)?.let { teleport(d, it) } }
        if (setup.resetStation) runCatching { lab.rebuild(setup.spot.substringBefore('.')) }
            .onFailure { plugin.logger.warning("GuardLab: Station ${setup.spot} nicht neu gebaut: ${it.message}") }
    }

    private fun resetPlayer(player: Player) {
        player.leaveVehicle()
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.inventory.clear()
        player.inventory.setArmorContents(arrayOfNulls(4))
        player.inventory.setItemInOffHand(null)
        player.isFlying = false
        player.allowFlight = false
        player.isGliding = false
        player.velocity = Vector(0, 0, 0)
        player.fallDistance = 0f
        player.fireTicks = 0
        player.freezeTicks = 0
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: player.health
        player.foodLevel = 20
        player.saturation = 20f
        player.walkSpeed = DEFAULT_WALK_SPEED
        player.flySpeed = DEFAULT_FLY_SPEED
    }

    private fun teleport(player: Player, pos: LabPos) {
        selfTeleport = true
        try {
            player.teleport(lab.location(pos))
        } finally {
            selfTeleport = false
        }
    }

    // ---------------------------------------------------------------- results

    private fun complete(player: Player?, active: Active) {
        val current = run ?: return
        if (frameStats === active.frames) frameStats = null
        stopIGuardTrace(current, active)
        runCatching { active.trace?.close() }
        val pings = active.pings
        val stats = active.frames?.takeIf { it.frameCount > 0 }
        val frames = stats?.summary()
        // Frame reports decide; VL rises of checks IGuard never reported in a frame still count.
        val checks = if (stats == null || frames == null) active.tracker.observations() else {
            val evaluated = frames.checks.map { it.check }.toSet()
            (stats.observations() + active.tracker.observations().filter { it.check !in evaluated }).sortedBy { it.check }
        }
        val observation = ScenarioObservation(
            checks = checks,
            setbacks = active.setbacks,
            clientFinished = active.finished,
            clientTicks = active.clientTicks,
            error = active.error,
            skipped = active.skipped,
            avgPing = if (pings.isEmpty()) 0.0 else pings.average(),
            maxPing = pings.maxOrNull() ?: 0,
            notes = active.notes.toList(),
            frames = frames,
        )
        val verdict = Evaluator.judge(active.scenario, observation, probe::alertVl)
        current.results += result(active.scenario, active.index, verdict, observation, active.traceName, active.startedAt, active.iguardName)
        plugin.logger.info("GuardLab: ${active.index + 1}/${current.scenarios.size} ${active.scenario.id} → ${verdict.status} (${verdict.reasons.firstOrNull().orEmpty()})")
        runCatching { cleanup(player, active) }.onFailure { plugin.logger.warning("GuardLab: Aufräumen fehlgeschlagen: ${it.message}") }
        current.active = null
        current.cooldownTicks = 0
        current.cooldownTarget = if (active.skipped != null) 0 else settings.cooldownTicks
        current.lastLevelSum = Double.MAX_VALUE
        current.stableTicks = 0
    }

    /** Starts IGuard's recording of the scenario (`<suite>-<scenario>`); without it only GuardLab's trace exists. */
    private fun startIGuardTrace(player: Player, active: Active) {
        val suite = run?.suite ?: return
        active.iguard = runCatching { probe.startTrace(player.uniqueId, "$suite-${active.scenario.id}") }
            .onFailure { plugin.logger.warning("GuardLab: IGuard-Trace nicht gestartet: ${it.message}") }
            .getOrNull()
        if (active.iguard != null) active.iguardName = "traces/%03d_%s.iguard.jsonl".format(active.index + 1, active.scenario.id)
    }

    /**
     * Stops IGuard's recording and copies it into the report directory (async; IGuard completes the file
     * asynchronously). While the plugin shuts down the copy runs inline with a short wait.
     */
    private fun stopIGuardTrace(current: Run, active: Active) {
        val trace = active.iguard ?: return
        val name = active.iguardName ?: return
        mark(active, "end")
        if (!trace.active) active.notes += "IGuard-Trace vorzeitig beendet (trace.max-bytes/max-seconds) – nur der Anfang ist aufgezeichnet"
        runCatching { trace.stop() }
        active.iguard = null
        val source = trace.file
        val target = File(reportsDir, current.dirName + "/" + name).toPath()
        val copy = { wait: Long ->
            runCatching { IGuardTraceCopy.copy(source, target, wait) }
                .onSuccess { complete -> if (!complete) plugin.logger.warning("GuardLab: IGuard-Trace $source unvollständig kopiert") }
                .onFailure { plugin.logger.warning("GuardLab: IGuard-Trace $source nicht kopiert: ${it.message}") }
        }
        if (plugin.isEnabled) {
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { copy(TRACE_COPY_WAIT_MILLIS) })
        } else {
            copy(TRACE_COPY_SHUTDOWN_WAIT_MILLIS)
        }
    }

    private fun finish(current: Run, aborted: String?) {
        current.task?.cancel()
        run = null
        frameStats = null
        runCatching { frameListener?.close() }
        frameListener = null
        val report = writeReport(current, aborted)
        plugin.server.getPlayer(current.client.playerId)?.let { player ->
            val passed = report.count(Status.PASS) + report.count(Status.WARN)
            send(player, Protocol.done("plugins/GuardLab/reports/${current.dirName}/report.md", passed, report.count(Status.FAIL)))
        }
        if (settings.quitAfterRun) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                plugin.server.getPlayer(current.client.playerId)?.let { send(it, Protocol.quit()) }
                dummy()?.let { send(it, Protocol.quit()) }
            }, QUIT_DELAY_TICKS)
        }
        toxiproxy?.let { client -> async { client.clear() } }
    }

    private fun writeReport(current: Run, aborted: String?): RunReport {
        val info = RunInfo(
            suite = current.suite,
            player = current.client.name,
            clientVersion = current.client.version,
            clientBrand = current.client.brand,
            service = service,
            startedAt = current.startedAt.toString(),
            finishedAt = Instant.now().toString(),
            aborted = aborted,
            dummy = current.dummy,
            toxiproxy = current.toxiproxy,
            probe = probe.description,
            sanctions = probe.sanctionsMode,
        )
        val results = current.results.sortedBy { it.index }
        // Alert levels as IGuard reported them in its frames; the config file is the fallback.
        val alerts = results.flatMap { it.observation.frames?.checks.orEmpty() }.filter { !it.alertVl.isNaN() }.associate { it.check to it.alertVl }
        val calibration = Reports.calibration(current.catalog, results) { check -> alerts[check] ?: probe.alertVl(check) }
        val report = RunReport(info, results, Reports.coverage(current.catalog, results), calibration)
        runCatching {
            val dir = File(reportsDir, current.dirName).also { it.mkdirs() }
            File(dir, "report.md").writeText(Reports.markdown(report))
            File(dir, "report.json").writeText(Reports.json(report))
            File(reportsDir, "latest.txt").writeText(current.dirName + "\n" + Reports.summary(report) + "\n")
        }.onFailure { plugin.logger.severe("GuardLab: Bericht nicht schreibbar: ${it.message}") }
        plugin.logger.info("GuardLab: Bericht reports/${current.dirName}/report.md – ${Reports.summary(report)}")
        onReport(report, current.dirName)
        return report
    }

    private fun result(
        scenario: Scenario,
        index: Int,
        verdict: Verdict,
        observation: ScenarioObservation,
        trace: String?,
        startedAt: Instant,
        iguardTrace: String? = null,
    ) =
        ScenarioResult(
            index = index,
            id = scenario.id,
            title = scenario.title,
            category = scenario.category.name.lowercase(),
            cheat = scenario.cheatMode,
            lag = scenario.lag,
            status = verdict.status,
            reasons = verdict.reasons,
            expected = scenario.expect.checks + scenario.expect.all,
            observation = observation,
            trace = trace,
            startedAt = startedAt.toString(),
            durationMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli(),
            exempt = verdict.exempt,
            iguardTrace = iguardTrace,
            expectation = Reports.expectation(scenario),
            covers = if (scenario.expect.none) scenario.covers else (scenario.expect.checks + scenario.expect.all).distinct(),
        )

    private fun skippedResult(scenario: Scenario, index: Int, reason: String) =
        result(scenario, index, Verdict(Status.SKIP, listOf(reason)), ScenarioObservation(skipped = reason), null, Instant.now())

    private fun send(player: Player, message: ByteArray) {
        if (Protocol.CHANNEL in player.listeningPluginChannels) player.sendPluginMessage(plugin, Protocol.CHANNEL, message)
    }

    private fun async(block: () -> Unit) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { runCatching(block) })
    }

    private companion object {
        const val BYPASS = "iguard.bypass"
        const val WAIT_SPOT = "flat.wait"
        const val DUMMY_SPOT = "flat.dummy"
        const val REDSTONE_BLOCK = "minecraft:redstone_block"
        const val AIR = "minecraft:air"
        const val MOUNT_TICK = 5
        const val LAG_WAIT_TICKS = 100
        const val START_TIMEOUT_TICKS = 100
        const val PLAY_GRACE_TICKS = 100
        const val TAIL_TICKS = 20
        const val LAG_TAIL_TICKS = 40
        const val PULSE_LENGTH = 6
        const val DECAYED = 0.5
        const val SETTLE_TICKS = 20
        const val QUIT_DELAY_TICKS = 40L
        const val TRACE_COPY_WAIT_MILLIS = 5_000L
        const val TRACE_COPY_SHUTDOWN_WAIT_MILLIS = 1_000L
        const val TARGET_HEALTH = 1024.0
        const val HORSE_SPEED = 0.3
        const val HORSE_JUMP = 0.7
        const val SEAT_OFFSET = -0.4
        const val DEFAULT_WALK_SPEED = 0.2f
        const val DEFAULT_FLY_SPEED = 0.1f
        val PLAYBACK = setOf(Phase.WAIT_START, Phase.PLAYING)
        val NO_CHEAT = buildJsonObject { put("mode", "none") }
        val DIR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
