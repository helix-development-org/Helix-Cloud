package org.helix.addons.guardlab.paper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Scenario category; suites select by category. */
enum class Category { LEGIT, CHEAT, LAG }

/**
 * Potion effect applied during setup.
 *
 * @property type Bukkit/Minecraft effect key, e.g. `speed`, `jump_boost`.
 * @property amplifier 0-based level.
 * @property seconds duration.
 */
@Serializable
data class EffectSpec(val type: String, val amplifier: Int = 0, val seconds: Int = 600)

/**
 * Item given during setup.
 *
 * @property slot `hotbar0`..`hotbar8`, `offhand`, `head`, `chest`, `legs`, `feet`.
 * @property material Bukkit material name.
 * @property amount stack size.
 * @property enchants enchantment key → level.
 */
@Serializable
data class ItemSpec(val slot: String, val material: String, val amount: Int = 1, val enchants: Map<String, Int> = emptyMap())

/**
 * Periodic knockback impulse (server-side velocity, as from a hit).
 *
 * @property everyTicks interval.
 * @property strength horizontal strength (blocks/tick), pushed against the player's facing.
 * @property upward vertical part.
 */
@Serializable
data class KnockbackSpec(val everyTicks: Int = 40, val strength: Double = 0.4, val upward: Double = 0.36)

/**
 * Everything the server prepares before the client plays a scenario.
 *
 * @property spot station spot `<station>.<spot>` (see [Stations]).
 * @property gamemode `survival`, `adventure`, `creative`.
 * @property effects potion effects.
 * @property items inventory and armour.
 * @property allowFlight grants flight (as admin/testtools fly).
 * @property flying starts flying.
 * @property vehicle `boat`, `minecart`, `horse` or `seat` (invisible armour stand, like furniture seats).
 * @property mob spot of an AI-less target mob (e.g. `combat.mob`).
 * @property knockback periodic knockback.
 * @property pistonPulseTicks interval of the slime-piston launcher.
 * @property resetStation rebuilds the station afterwards (build/break areas).
 * @property dummy spot of the dummy player client (e.g. `combat.dummy`); without a connected dummy the
 *   scenario is skipped.
 */
@Serializable
data class ScenarioSetup(
    val spot: String,
    val gamemode: String = "survival",
    val effects: List<EffectSpec> = emptyList(),
    val items: List<ItemSpec> = emptyList(),
    val allowFlight: Boolean = false,
    val flying: Boolean = false,
    val vehicle: String? = null,
    val mob: String? = null,
    val knockback: KnockbackSpec? = null,
    val pistonPulseTicks: Int? = null,
    val resetStation: Boolean = false,
    val dummy: String? = null,
)

/**
 * What a scenario must produce.
 *
 * @property none legit: no check may flag (sub-threshold flags are a warning, alert-level flags fail).
 * @property checks cheat: at least one of these checks must reach [minVl] in total.
 * @property all cheat: every one of these checks must reach [minVl].
 * @property minVl minimum summed violation increase per expected check.
 * @property setback `true` requires at least one setback, `false` forbids one, `null` ignores setbacks.
 * @property note shown in the report (reasoning, limits …).
 * @property knownGap cheat: IGuard is known not to detect this yet – a miss is listed as a known gap (status
 *   [Status.GAP]) instead of a failure, a detection as a hint that the gap may be closed.
 */
@Serializable
data class Expectation(
    val none: Boolean = false,
    val checks: List<String> = emptyList(),
    val all: List<String> = emptyList(),
    val minVl: Double = 1.0,
    val setback: Boolean? = null,
    val note: String = "",
    val knownGap: Boolean = false,
)

/**
 * One scenario of a suite.
 *
 * @property id unique id (`[a-z0-9_]`).
 * @property title short German description.
 * @property category legit, cheat or lag.
 * @property setup server-side preparation.
 * @property script client step list (passed through unchanged).
 * @property dummyScript step list for the dummy client (combat scenarios).
 * @property cheat `{mode, params}` sent to the client.
 * @property setupTicks ticks between setup and playback (lets teleport exemptions expire).
 * @property durationTicks maximum playback ticks.
 * @property lag Toxiproxy profile.
 * @property expect expectation.
 * @property covers checks a legit scenario exercises (coverage matrix).
 */
data class Scenario(
    val id: String,
    val title: String,
    val category: Category,
    val setup: ScenarioSetup,
    val script: JsonArray,
    val dummyScript: JsonArray,
    val cheat: JsonObject,
    val setupTicks: Int,
    val durationTicks: Int,
    val lag: String,
    val expect: Expectation,
    val covers: List<String>,
) {
    /** Cheat mode (`none` for legit scenarios). */
    val cheatMode: String get() = (cheat["mode"] as? JsonPrimitive)?.content ?: "none"

    /** Client tick at which the cheat switches on (`params.startTick`, 0 = from the start). */
    val cheatStartTick: Int get() = ((cheat["params"] as? JsonObject)?.get("startTick") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
}

/**
 * The parsed suite file.
 *
 * @property scenarios all scenarios in file order.
 * @property suites suite id → scenario ids in run order.
 */
data class SuiteCatalog(val scenarios: Map<String, Scenario>, val suites: Map<String, List<String>>) {
    /**
     * Resolves a suite.
     *
     * @param id suite id.
     * @return the scenarios, or null for an unknown suite.
     */
    fun suite(id: String): List<Scenario>? = suites[id]?.mapNotNull { scenarios[it] }

    /** Every check id some scenario exercises or expects. */
    val coveredChecks: Set<String>
        get() = scenarios.values.flatMap { it.covers + it.expect.checks + it.expect.all }.toSet()
}

/**
 * Parses and validates the suite file (`suites.json`).
 *
 * Suites list scenario ids or category selectors (`@legit`, `@cheat`, `@lag`); a scenario listed more than once
 * runs at its last position (`all` moves the once-per-connection client scenarios behind `@lag` that way).
 * Unknown ids, duplicate ids, unknown spots, cheat modes, lag profiles or step fields are errors, so a broken
 * file fails loudly at start.
 */
object SuiteParser {
    /** Cheat modes of the client contract. */
    val CHEAT_MODES = setOf(
        "none", "speed", "fly", "timer", "reach", "killaura", "nofall", "step", "scaffold", "fastbreak",
        "autoclicker", "antiknockback", "blink", "phase", "omnisprint", "inventory", "identity", "brandspoof", "badpackets",
    )

    /** Lag profiles understood by [LagProfiles]. */
    val LAG_PROFILES = setOf("none", "ping100", "ping250_jitter50", "loss2", "spike")

    /** Fields a script step may carry. */
    val STEP_FIELDS = setOf("ticks", "keys", "yaw", "pitch", "lookAt", "attack", "use", "place", "hotbar", "chat")

    /** Keys a step may hold down. */
    val STEP_KEYS = setOf("forward", "back", "left", "right", "jump", "sprint", "sneak")

    private val json = Json { ignoreUnknownKeys = false }
    private val ID = Regex("^[a-z0-9_]{1,48}$")

    /**
     * Parses [text].
     *
     * @param text suite file content.
     * @param spotExists validates `<station>.<spot>` references.
     * @return the catalog.
     * @throws IllegalArgumentException describing every problem found.
     */
    fun parse(text: String, spotExists: (String) -> Boolean = { true }): SuiteCatalog {
        val root = json.parseToJsonElement(text).jsonObject
        val errors = mutableListOf<String>()
        val scenarios = linkedMapOf<String, Scenario>()
        root.arr("scenarios").orEmpty().forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: run { errors += "scenarios[$index]: no object"; return@forEachIndexed }
            val id = obj.str("id")
            if (id == null || !ID.matches(id)) { errors += "scenarios[$index]: invalid id '$id'"; return@forEachIndexed }
            if (id in scenarios) { errors += "$id: duplicate id"; return@forEachIndexed }
            runCatching { scenario(id, obj, spotExists, errors) }
                .onSuccess { scenarios[id] = it }
                .onFailure { errors += "$id: ${it.message}" }
        }
        val suites = linkedMapOf<String, List<String>>()
        root.obj("suites")?.forEach { (suiteId, value) ->
            val entries = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: run {
                errors += "suite $suiteId: no list"; return@forEach
            }
            val ids = entries.flatMap { entry ->
                if (entry.startsWith("@")) {
                    val category = runCatching { Category.valueOf(entry.drop(1).uppercase()) }.getOrNull()
                    if (category == null) { errors += "suite $suiteId: unknown selector $entry"; emptyList() } else {
                        scenarios.values.filter { it.category == category }.map { it.id }
                    }
                } else {
                    if (entry !in scenarios) errors += "suite $suiteId: unknown scenario $entry"
                    listOf(entry)
                }
            }
            // Keep the last occurrence: an id listed after a selector moves the scenario there.
            suites[suiteId] = ids.asReversed().distinct().asReversed()
        }
        require(errors.isEmpty()) { "suite file invalid:\n" + errors.joinToString("\n") }
        return SuiteCatalog(scenarios, suites)
    }

    private fun scenario(id: String, obj: JsonObject, spotExists: (String) -> Boolean, errors: MutableList<String>): Scenario {
        val category = obj.str("category")?.let { runCatching { Category.valueOf(it.uppercase()) }.getOrNull() }
            ?: error("unknown category '${obj.str("category")}'")
        val setup = json.decodeFromJsonElement<ScenarioSetup>(obj.obj("setup") ?: error("missing setup"))
        if (!spotExists(setup.spot)) errors += "$id: unknown spot ${setup.spot}"
        setup.vehicle?.let { if (it !in VEHICLES) errors += "$id: unknown vehicle $it" }
        setup.mob?.let { if (!spotExists(it)) errors += "$id: unknown mob spot $it" }
        setup.dummy?.let { if (!spotExists(it)) errors += "$id: unknown dummy spot $it" }
        if (setup.dummy == null && obj.arr("dummyScript")?.isNotEmpty() == true) errors += "$id: dummyScript without dummy spot"
        val script = obj.arr("script") ?: JsonArray(emptyList())
        val dummyScript = obj.arr("dummyScript") ?: JsonArray(emptyList())
        (script + dummyScript).forEachIndexed { i, step -> validateStep("$id step $i", step, errors) }
        val cheat = obj.obj("cheat") ?: buildJsonObject { put("mode", "none") }
        val mode = cheat.str("mode") ?: "none"
        if (mode !in CHEAT_MODES) errors += "$id: unknown cheat mode $mode"
        val lag = obj.str("lag") ?: "none"
        if (lag !in LAG_PROFILES) errors += "$id: unknown lag profile $lag"
        val expect = json.decodeFromJsonElement<Expectation>(obj.obj("expect") ?: error("missing expect"))
        if (!expect.none && expect.checks.isEmpty() && expect.all.isEmpty()) errors += "$id: expect needs none or checks"
        if (expect.none && (expect.checks.isNotEmpty() || expect.all.isNotEmpty())) errors += "$id: expect none excludes checks"
        if (category == Category.CHEAT && expect.none) errors += "$id: a cheat scenario must expect checks"
        if (expect.knownGap && expect.none) errors += "$id: knownGap needs expected checks"
        val scriptTicks = script.sumOf { ((it as? JsonObject)?.get("ticks") as? JsonPrimitive)?.intOrNull ?: 0 }
        val duration = obj.int("durationTicks") ?: (scriptTicks + 20)
        if (duration < scriptTicks) errors += "$id: durationTicks $duration shorter than the script ($scriptTicks)"
        return Scenario(
            id = id,
            title = obj.str("title") ?: id,
            category = category,
            setup = setup,
            script = script,
            dummyScript = dummyScript,
            cheat = cheat,
            setupTicks = obj.int("setupTicks") ?: 60,
            durationTicks = duration,
            lag = lag,
            expect = expect,
            covers = obj.arr("covers")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
        )
    }

    private fun validateStep(where: String, step: JsonElement, errors: MutableList<String>) {
        val obj = step as? JsonObject ?: run { errors += "$where: no object"; return }
        (obj.keys - STEP_FIELDS).takeIf { it.isNotEmpty() }?.let { errors += "$where: unknown fields $it" }
        val ticks = (obj["ticks"] as? JsonPrimitive)?.intOrNull
        if (ticks == null || ticks !in 1..2400) errors += "$where: ticks must be 1..2400"
        obj.arr("keys")?.forEach { key ->
            val k = (key as? JsonPrimitive)?.content
            if (k !in STEP_KEYS) errors += "$where: unknown key $k"
        }
    }

    private val VEHICLES = setOf("boat", "minecart", "horse", "seat")
}
