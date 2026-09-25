package org.helix.addons.mineclash.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Definition of one typed setting: key, value type, default and a human readable purpose.
 *
 * @property key setting key without namespace, for example `war.pointsWin`.
 * @property type value type (`INT`, `DOUBLE`, `BOOL`, `STRING`, `INT_LIST`, `STRING_LIST`).
 * @property default default value rendered as text.
 * @property description purpose shown in the dashboard panel.
 */
data class SettingDefinition(val key: String, val type: String, val default: String, val description: String) {
    /**
     * Validates and normalizes a raw value against [type].
     *
     * @param raw the raw text.
     * @return the normalized value.
     * @throws IllegalArgumentException when the value does not match the type.
     */
    fun normalize(raw: String): String {
        val value = raw.trim()
        when (type) {
            "INT" -> require(value.toIntOrNull() != null) { "'$value' is not an INT" }
            "DOUBLE" -> require(value.toDoubleOrNull() != null) { "'$value' is not a DOUBLE" }
            "BOOL" -> require(value.equals("true", true) || value.equals("false", true)) { "'$value' is not a BOOL" }
            "INT_LIST" -> require(value.isEmpty() || value.split(',').all { it.trim().toIntOrNull() != null }) { "'$value' is not an INT_LIST" }
        }
        return if (type == "BOOL") value.lowercase() else value
    }
}

/**
 * Initial MineClash configuration: the former contents of the game's YAML files. Seeded into the
 * config store when a key or document is missing; afterwards the store is the single source of
 * truth and the panel edits it.
 */
object ConfigSeeds {
    /** Task name of the village/arena game servers (document id in the `servers` collection). */
    const val GAME_TASK: String = "MineClash-Game"

    /** Task name of the clan war servers. */
    const val WAR_TASK: String = "MineClash-War"

    private val rarities = listOf("NORMAL", "SPECIAL", "ELITE", "TITAN", "BOSS")

    /** All typed settings with defaults. */
    val settings: List<SettingDefinition> = buildList {
        /** Adds an INT setting. */
        fun int(k: String, v: Int, d: String) = add(SettingDefinition(k, "INT", v.toString(), d))
        /** Adds a DOUBLE setting. */
        fun dbl(k: String, v: Double, d: String) = add(SettingDefinition(k, "DOUBLE", v.toString(), d))
        /** Adds a BOOL setting. */
        fun bool(k: String, v: Boolean, d: String) = add(SettingDefinition(k, "BOOL", v.toString(), d))
        /** Adds a STRING setting. */
        fun str(k: String, v: String, d: String) = add(SettingDefinition(k, "STRING", v, d))
        /** Adds an INT_LIST setting. */
        fun ints(k: String, v: List<Int>, d: String) = add(SettingDefinition(k, "INT_LIST", v.joinToString(","), d))
        /** Adds a STRING_LIST setting. */
        fun strs(k: String, v: List<String>, d: String) = add(SettingDefinition(k, "STRING_LIST", v.joinToString(","), d))

        str("queue.gameTask", GAME_TASK, "Task whose services host village and arena matches")
        str("war.task", WAR_TASK, "Task whose services host clan wars")
        int("queue.timeoutSeconds", 600, "Seconds a player stays queued before being dropped")
        int("clans.buildingIndex", 4, "Village house index of the clan hall that unlocks clan features (-1 = none)")
        bool("towers.decor", true, "Render tower crowns as 3D models (needs the network pack)")
        strs("deck.starter", listOf("zombie", "skelettbogenschuetze", "hoehlenspinne", "snow_golem", "evoker", "phantom", "witch", "blaze"), "Starter deck of new players (order = priority)")
        int("deck.minSize", 4, "Minimum playable deck size")
        int("deck.maxSize", 8, "Deck slots of every player (fixed, independent of the village level)")
        int("cardLevels.maxLevel", 5, "Highest card level")
        ints("cardLevels.copies", listOf(2, 4, 8, 15), "Copies per level-up (1→2, 2→3, …)")
        ints("cardLevels.emeralds", listOf(50, 150, 400, 1000), "Emeralds per level-up (× rarity multiplier)")
        rarities.zip(listOf(1.0, 1.5, 2.0, 3.0, 4.0)).forEach { (r, m) -> dbl("cardLevels.rarityMultiplier.$r", m, "Level-up cost multiplier for $r cards") }
        dbl("cardLevels.bonusPerLevel", 0.10, "Bonus on health/damage/tower damage per level")
        dbl("perks.speedPerLevel", 0.10, "Chest opening time −x per smith level")
        dbl("perks.luckPerLevel", 0.20, "Elite/Titan/Boss weights +x per smith level")
        dbl("level.rate.normal", 1.0, "Elixir level per second (regular phase)")
        dbl("level.rate.double", 2.0, "Elixir level per second (last minute)")
        dbl("level.rate.triple", 3.0, "Elixir level per second (sudden death)")
        rarities.zip(listOf(50, 80, 120, 200, 300)).forEach { (r, p) -> int("shop.cardPrice.$r", p, "Shop price of a $r card") }
        int("shop.emeraldOffer.diamonds", 10, "Diamond price of the emerald bundle")
        int("shop.emeraldOffer.amount", 100, "Emeralds in the emerald bundle")
        int("match.winRoses", 30, "Roses for a match win")
        int("match.loseRoses", 20, "Roses lost on a defeat")
        int("tutorial.startEmeralds", 100, "Tutorial start emeralds")
        int("tutorial.matchReward", 300, "Reward for the first match")
        int("tutorial.finishReward", 200, "Reward for finishing the tutorial")
        int("war.worldSize", 2000, "Edge length of the war world")
        int("war.maxPlayersPerClan", 10, "Players per clan in a war")
        int("war.timeLimitMinutes", 0, "Time limit in minutes (0 = points/base only)")
        int("war.pointsWin", 5000, "Points needed to win")
        int("war.pointsKill", 50, "Points per enemy kill")
        int("war.pointsCapture", 300, "Points per arena capture")
        int("war.pointsHold", 20, "Points per hold interval per arena")
        int("war.holdIntervalSeconds", 60, "Hold interval in seconds")
        int("war.arenaCount", 6, "Number of arenas")
        int("war.arenaHp", 100000, "Health of an arena crystal")
        int("war.baseHp", 500000, "Health of the base crystal")
        int("war.towerHp", 50000, "Health of encounter towers")
        dbl("war.cardMobChance", 0.20, "Share of natural spawns that are card mobs")
        int("war.spectateSeconds", 10, "Spectator time after death")
        int("war.baseLoadedRadius", 100, "Force-load radius around bases")
        int("war.startPodests", 3, "Defense pedestals per base at start")
        int("war.podestRespawnSeconds", 120, "Seconds until fallen pedestal guardians return with the same card")
        dbl("war.troopPlayerDamageFactor", 0.5, "Multiplier on card damage when troops hit players")
        bool("war.keepCards", false, "Keep cards earned in the war")
        int("war.arenaDefenseSlots", 3, "Defense slots per arena")
        int("war.arenaDefenseCooldownSeconds", 30, "Arena defense cooldown")
        int("war.encounterRadius", 100, "Radius of an encounter zone")
        int("war.encounterTowerDistance", 30, "Tower distance from the zone center")
        int("war.encounterTriggerDistance", 25, "Distance at which enemies trigger a zone")
        int("war.maxDeckCards", 8, "Maximum cards in a war deck")
        int("war.baseZoneRadius", 50, "Radius of the base zone sphere (combat zone, defender patrol area)")
        int("war.baseZoneRevealDistance", 5, "The base sphere is only visible within this distance of its surface (0 = always)")
        int("war.arenaZoneRadius", 12, "Visible sphere around arena crystals")
        int("war.baseMinDistance", 1000, "Minimum distance between the two bases (blocks)")
        int("war.baseBorderMargin", 150, "Minimum distance of bases from the world border")
        str("war.biomeMode", "CHUNK", "Biome layout: CHUNK = every chunk a random biome, ZONES = regions of war.biomeCellSize")
        int("war.biomeCellSize", 280, "Size of one biome zone in ZONES mode; every land biome gets at least one")
        int("war.endBorder", 400, "World border edge length of the war End")
        bool("war.stronghold", true, "Place a stronghold with an End portal in every war world")
        int("war.strongholdMinBaseDistance", 350, "Minimum distance of the stronghold from both bases")
        int("war.customSpawnIntervalSeconds", 10, "Interval of the rule-based card mob spawner")
        dbl("war.customSpawnChance", 0.5, "Chance per player and interval to attempt a rule-based card mob")
        int("war.customSpawnMaxPerPlayer", 4, "Maximum rule-based card mobs near one player")
    }

    /** Collections (name → documents) with their seed content. */
    val collections: Map<String, List<ConfigDocument>> by lazy {
        linkedMapOf(
            "cards" to cards(),
            "chests" to chests(),
            "buildings" to buildings(),
            "perks" to perks(),
            "servers" to servers(),
            "warSpawns" to warSpawns(),
        )
    }

    private fun cards(): List<ConfigDocument> {
        val text = ConfigSeeds::class.java.getResourceAsStream("/seed/cards.json")?.bufferedReader()?.readText()
            ?: error("seed/cards.json is missing from the addon jar")
        return Json.parseToJsonElement(text).jsonArray.map { element ->
            val obj = element.jsonObject
            ConfigDocument(
                id = obj.getValue("id").jsonPrimitive.content,
                sortOrder = obj["sortOrder"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                body = obj.getValue("body").jsonObject,
            )
        }
    }

    private fun chests(): List<ConfigDocument> {
        /** Builds one chest document. */
        fun chest(rarity: String, order: Int, weight: Int, openSeconds: Int, emeralds: Int, diamonds: Int, cards: Int, instant: Int, weights: List<Int>) =
            ConfigDocument(rarity, order, buildJsonObject {
                put("weight", JsonPrimitive(weight)); put("openSeconds", JsonPrimitive(openSeconds)); put("emeralds", JsonPrimitive(emeralds))
                put("diamonds", JsonPrimitive(diamonds)); put("cards", JsonPrimitive(cards)); put("instantDiamonds", JsonPrimitive(instant))
                put("cardWeights", buildJsonObject { rarities.forEachIndexed { i, r -> put(r, JsonPrimitive(weights[i])) } })
            })
        return listOf(
            chest("NORMAL", 0, 55, 600, 15, 0, 2, 5, listOf(50, 25, 15, 7, 3)),
            chest("SPECIAL", 1, 25, 1800, 30, 0, 3, 10, listOf(35, 30, 20, 10, 5)),
            chest("ELITE", 2, 12, 3600, 60, 0, 4, 15, listOf(20, 30, 28, 15, 7)),
            chest("TITAN", 3, 6, 10800, 90, 2, 5, 20, listOf(10, 20, 30, 28, 12)),
            chest("BOSS", 4, 2, 28800, 120, 5, 6, 25, listOf(5, 10, 25, 35, 25)),
            chest("WAR_WIN", 5, 0, 3600, 150, 5, 6, 20, listOf(5, 15, 30, 30, 20)),
            chest("WAR_LOSE", 6, 0, 1800, 40, 0, 3, 10, listOf(40, 30, 20, 7, 3)),
        )
    }

    /**
     * Rule-based card mob spawns of the war (document id = card id): cards whose mob never spawns
     * naturally in the war overworld. `dimension` OVERWORLD/NETHER/END, `biomes` empty = any,
     * `underground` = only below the surface, `weight` relative pick weight, `chance` extra roll after
     * the pick (rare bosses), `maxAlive` cap per war, `boss` = the real boss of that dimension carries
     * the card (no spawner).
     */
    private fun warSpawns(): List<ConfigDocument> {
        fun rule(card: String, order: Int, dimension: String, biomes: List<String> = emptyList(), underground: Boolean = false,
                 maxY: Int = 320, weight: Int = 1, chance: Double = 1.0, maxAlive: Int = 3, boss: Boolean = false) =
            ConfigDocument(card, order, buildJsonObject {
                put("dimension", JsonPrimitive(dimension))
                put("biomes", buildJsonArray { biomes.forEach { add(JsonPrimitive(it)) } })
                put("underground", JsonPrimitive(underground)); put("maxY", JsonPrimitive(maxY))
                put("weight", JsonPrimitive(weight)); put("chance", JsonPrimitive(chance))
                put("maxAlive", JsonPrimitive(maxAlive)); put("boss", JsonPrimitive(boss))
            })
        val snowy = listOf("SNOWY_PLAINS", "ICE_SPIKES", "SNOWY_TAIGA", "GROVE", "SNOWY_SLOPES", "FROZEN_PEAKS", "SNOWY_BEACH")
        val village = listOf("PLAINS", "SUNFLOWER_PLAINS", "MEADOW", "SAVANNA", "DESERT", "TAIGA", "SNOWY_PLAINS")
        return listOf(
            rule("hoehlenspinne", 0, "OVERWORLD", underground = true, maxY = 40, weight = 3, maxAlive = 6),
            rule("snow_golem", 1, "OVERWORLD", snowy, weight = 2, maxAlive = 4),
            rule("evoker", 2, "OVERWORLD", listOf("DARK_FOREST", "PALE_GARDEN"), weight = 1, maxAlive = 2),
            rule("iron_golem", 3, "OVERWORLD", village, weight = 1, maxAlive = 2),
            rule("blaze", 4, "NETHER", weight = 3, maxAlive = 6),
            rule("wither", 5, "NETHER", listOf("SOUL_SAND_VALLEY"), weight = 1, chance = 0.1, maxAlive = 1),
            rule("enderdragon", 6, "END", boss = true, maxAlive = 1),
        )
    }

    private fun ints(values: List<Int>): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun buildings(): List<ConfigDocument> {
        /** Builds one building document. */
        fun building(type: String, order: Int, costs: List<Int>, perHour: List<Int>? = null, perDay: List<Int>? = null, capacity: Int? = null) =
            ConfigDocument(type, order, buildJsonObject {
                put("costs", ints(costs))
                perHour?.let { put("emeraldsPerHour", ints(it)) }
                perDay?.let { put("diamondsPerDay", ints(it)) }
                capacity?.let { put("capacityHours", JsonPrimitive(it)) }
            })
        return listOf(
            building("HOME", 0, listOf(300)),
            building("TOWN_HALL", 1, listOf(500, 1500)),
            building("MINE", 2, listOf(400, 1000, 2500), listOf(10, 20, 35), listOf(0, 0, 3), 8),
            building("CLAN_HALL", 3, listOf(2000)),
        )
    }

    private fun perks(): List<ConfigDocument> = listOf("CHEST_SPEED", "CHEST_LUCK").mapIndexed { i, key ->
        ConfigDocument(key, i, buildJsonObject { put("emeralds", ints(listOf(300, 800, 2000))); put("diamonds", ints(listOf(2, 5, 10))) })
    }

    private fun servers(): List<ConfigDocument> {
        /** Builds one server-role document. */
        fun server(task: String, order: Int, mode: String) =
            ConfigDocument(task, order, buildJsonObject { put("mode", JsonPrimitive(mode)); put("enabled", JsonPrimitive(true)) })
        return listOf(server(GAME_TASK, 0, "VILLAGE"), server(WAR_TASK, 1, "WAR"))
    }

    /**
     * Serializes a JSON object to text.
     *
     * @param value the object.
     * @return compact JSON text.
     */
    fun render(value: JsonObject): String = value.toString()
}
