package org.helix.addons.citybuild.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Seed documents of every CityBuild config collection, built from the game concept.
 *
 * Collections and their document bodies (field names are the contract with the Paper plugin, see
 * `ACTIONS.md`):
 * - `servers` – one document per task (`CityBuild`): `mode`, `enabled`, `instances`.
 * - `adminshop_categories` – merchant tabs: `name`, `icon`.
 * - `adminshop` – one ware per material: `material`, `category`, `buy`, `sell` (Kronen per `unit`
 *   items, `null` = not offered), `unit`, `dailyLimit` (`null` = `adminshop.defaultItemLimit`).
 * - `jobs` – `name`, `icon`, `villager`, `worlds`, `plots`, `actions` (`"<kind>:<KEY>"` → base Kronen).
 * - `kits` – `name`, `icon`, `cooldownSeconds`, `once`, `minTier`, `items`.
 * - `rewards` – `day1`…`day7`, `job_<level>`, `tutorial`: `kronen`, `items`, `keys`.
 * - `limits` – `tier0`…`tier3`.
 * - `villagers` – the CityBuild cast.
 * - `enchants`, `abilities` – Kronland enchantments and forge abilities with their parameters.
 * - `crates` – crate specs ([org.helix.addons.citybuild.service.CrateSpec]).
 * - `forge`, `offers`, `arenas` – created at runtime (no seeds).
 */
object SeedCollections {
    /**
     * Builds all seed collections.
     *
     * @return collection name to its seed documents.
     */
    fun all(): Map<String, List<ConfigDocument>> = linkedMapOf(
        "servers" to listOf(
            ConfigDocument(
                ConfigSeeds.TASK,
                0,
                obj("mode" to "CITYBUILD", "enabled" to true, "instances" to ConfigSeeds.INSTANCES),
            ),
        ),
        "adminshop_categories" to categories(),
        "adminshop" to wares(),
        "jobs" to jobs(),
        "kits" to kits(),
        "rewards" to rewards(),
        "limits" to limits(),
        "villagers" to villagers(),
        "enchants" to enchants(),
        "abilities" to abilities(),
        "crates" to crates(),
    )

    private val woods = listOf("OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY", "PALE_OAK")
    private val colors = listOf(
        "WHITE", "LIGHT_GRAY", "GRAY", "BLACK", "BROWN", "RED", "ORANGE", "YELLOW",
        "LIME", "GREEN", "CYAN", "LIGHT_BLUE", "BLUE", "PURPLE", "MAGENTA", "PINK",
    )

    private fun categories(): List<ConfigDocument> = listOf(
        "baubloecke" to ("Baublöcke" to "STONE_BRICKS"),
        "holz" to ("Holz" to "OAK_LOG"),
        "natur" to ("Natur" to "GRASS_BLOCK"),
        "erze" to ("Erze" to "DIAMOND"),
        "farm" to ("Farm" to "WHEAT"),
        "mobdrops" to ("Mob-Drops" to "BONE"),
        "deko" to ("Deko" to "LANTERN"),
        "redstone" to ("Redstone" to "REDSTONE"),
    ).mapIndexed { i, (id, v) -> ConfigDocument(id, i, obj("name" to v.first, "icon" to v.second)) }

    private fun wares(): List<ConfigDocument> {
        val list = mutableListOf<ConfigDocument>()

        /** Adds one ware. */
        fun ware(material: String, category: String, buy: Long?, sell: Long?, unit: Int = 1, dailyLimit: Int? = null) {
            list += ConfigDocument(
                material.lowercase(),
                list.size,
                obj("material" to material, "category" to category, "buy" to buy, "sell" to sell, "unit" to unit, "dailyLimit" to dailyLimit),
            )
        }
        // Baublöcke (Kauf)
        listOf("STONE" to 2L, "COBBLESTONE" to 1L, "SMOOTH_STONE" to 3L, "STONE_BRICKS" to 3L, "BRICKS" to 4L, "ANDESITE" to 2L, "DIORITE" to 2L, "GRANITE" to 2L,
            "POLISHED_ANDESITE" to 3L, "POLISHED_DIORITE" to 3L, "POLISHED_GRANITE" to 3L, "DEEPSLATE_BRICKS" to 4L, "DEEPSLATE_TILES" to 4L, "SANDSTONE" to 2L,
            "SMOOTH_SANDSTONE" to 3L, "RED_SANDSTONE" to 3L, "MUD_BRICKS" to 3L, "BLACKSTONE" to 3L, "POLISHED_BLACKSTONE_BRICKS" to 4L, "PRISMARINE" to 5L,
            "QUARTZ_BLOCK" to 6L, "QUARTZ_BRICKS" to 6L, "GLASS" to 3L, "TERRACOTTA" to 3L, "CALCITE" to 3L, "TUFF_BRICKS" to 4L)
            .forEach { (m, p) -> ware(m, "baubloecke", p, null) }
        colors.forEach { c ->
            ware("${c}_CONCRETE", "baubloecke", 4, null)
            ware("${c}_TERRACOTTA", "baubloecke", 3, null)
            ware("${c}_STAINED_GLASS", "baubloecke", 4, null)
            ware("${c}_WOOL", "baubloecke", 3, null)
        }
        // Holz
        woods.forEach { w ->
            ware("${w}_LOG", "holz", 3, 1)
            ware("${w}_PLANKS", "holz", 1, null)
        }
        ware("CRIMSON_STEM", "holz", 3, 1)
        ware("WARPED_STEM", "holz", 3, 1)
        ware("CRIMSON_PLANKS", "holz", 1, null)
        ware("WARPED_PLANKS", "holz", 1, null)
        ware("BAMBOO_BLOCK", "holz", 3, null)
        ware("BAMBOO_PLANKS", "holz", 1, null)
        // Natur
        listOf("DIRT" to 1L, "GRASS_BLOCK" to 3L, "COARSE_DIRT" to 1L, "PODZOL" to 2L, "SAND" to 1L, "RED_SAND" to 2L, "GRAVEL" to 1L, "CLAY" to 3L,
            "MOSS_BLOCK" to 3L, "MOSS_CARPET" to 1L, "SNOW_BLOCK" to 2L, "ICE" to 3L, "PACKED_ICE" to 4L, "LILY_PAD" to 2L, "VINE" to 2L, "MUD" to 1L)
            .forEach { (m, p) -> ware(m, "natur", p, null) }
        listOf("DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP",
            "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY")
            .forEach { ware(it, "natur", 2, null) }
        (woods.filter { it != "MANGROVE" }.map { "${it}_SAPLING" } + "MANGROVE_PROPAGULE").forEach { ware(it, "natur", 4, null) }
        // Erze (Ankauf)
        listOf("COAL" to 1L, "IRON_INGOT" to 4L, "COPPER_INGOT" to 1L, "GOLD_INGOT" to 6L, "REDSTONE" to 1L, "LAPIS_LAZULI" to 1L,
            "DIAMOND" to 40L, "EMERALD" to 30L, "QUARTZ" to 1L, "AMETHYST_SHARD" to 1L)
            .forEach { (m, p) -> ware(m, "erze", null, p) }
        // Farm: Samen kaufen, Ernte im 16er-Bündel verkaufen
        listOf("WHEAT_SEEDS", "BEETROOT_SEEDS", "PUMPKIN_SEEDS", "MELON_SEEDS", "TORCHFLOWER_SEEDS").forEach { ware(it, "farm", 2, null) }
        listOf("WHEAT", "CARROT", "POTATO", "BEETROOT", "PUMPKIN", "MELON_SLICE", "SUGAR_CANE", "CACTUS", "COCOA_BEANS", "NETHER_WART", "SWEET_BERRIES")
            .forEach { ware(it, "farm", null, 4, unit = 16) }
        listOf("BAMBOO", "KELP").forEach { ware(it, "farm", null, 2, unit = 16) }
        // Mob-Drops (Ankauf)
        listOf("BONE" to 1L, "STRING" to 1L, "GUNPOWDER" to 3L, "LEATHER" to 2L, "FEATHER" to 1L, "SPIDER_EYE" to 1L, "ENDER_PEARL" to 3L, "BLAZE_ROD" to 3L,
            "SLIME_BALL" to 2L, "INK_SAC" to 1L, "PHANTOM_MEMBRANE" to 3L, "GHAST_TEAR" to 3L, "MAGMA_CREAM" to 2L)
            .forEach { (m, p) -> ware(m, "mobdrops", null, p) }
        ware("ROTTEN_FLESH", "mobdrops", null, 1, unit = 16)
        // Deko (Kauf)
        listOf("LANTERN" to 6L, "SOUL_LANTERN" to 8L, "CHAIN" to 4L, "BOOKSHELF" to 12L, "CHISELED_BOOKSHELF" to 20L, "FLOWER_POT" to 4L, "CAMPFIRE" to 8L,
            "CANDLE" to 4L, "PAINTING" to 6L, "ITEM_FRAME" to 6L, "ARMOR_STAND" to 10L, "END_ROD" to 10L, "SEA_LANTERN" to 12L, "GLOWSTONE" to 8L,
            "SHROOMLIGHT" to 10L, "DECORATED_POT" to 8L)
            .forEach { (m, p) -> ware(m, "deko", p, null) }
        colors.forEach { ware("${it}_BANNER", "deko", 10, null) }
        // Redstone (Kauf)
        listOf("PISTON" to 10L, "STICKY_PISTON" to 15L, "OBSERVER" to 12L, "HOPPER" to 25L, "RAIL" to 8L, "POWERED_RAIL" to 15L, "DETECTOR_RAIL" to 12L,
            "ACTIVATOR_RAIL" to 12L, "REPEATER" to 8L, "COMPARATOR" to 12L, "LEVER" to 8L, "STONE_BUTTON" to 8L, "DISPENSER" to 15L, "DROPPER" to 12L,
            "DAYLIGHT_DETECTOR" to 15L, "TARGET" to 10L, "REDSTONE_LAMP" to 12L, "NOTE_BLOCK" to 10L, "TRIPWIRE_HOOK" to 8L)
            .forEach { (m, p) -> ware(m, "redstone", p, null) }
        return list
    }

    private fun jobs(): List<ConfigDocument> {
        val farmWorlds = listOf("farm", "farm_nether", "farm_end")
        val logs = (woods.map { "${it}_LOG" } + listOf("CRIMSON_STEM", "WARPED_STEM")).associate { "break:$it" to 0.25 }
        val miner = buildMap {
            listOf("STONE", "DEEPSLATE", "TUFF", "NETHERRACK", "BLACKSTONE", "BASALT", "END_STONE").forEach { put("break:$it", 0.05) }
            /** Adds an ore and its deepslate variant. */
            fun ore(name: String, value: Double) {
                put("break:$name", value)
                put("break:DEEPSLATE_$name", value)
            }
            ore("COAL_ORE", 0.5); ore("IRON_ORE", 1.0); ore("COPPER_ORE", 1.0)
            ore("GOLD_ORE", 1.5); ore("REDSTONE_ORE", 1.5); ore("LAPIS_ORE", 1.5)
            ore("DIAMOND_ORE", 8.0); ore("EMERALD_ORE", 10.0)
            put("break:NETHER_GOLD_ORE", 1.5); put("break:NETHER_QUARTZ_ORE", 1.0); put("break:ANCIENT_DEBRIS", 25.0)
        }
        val digger = listOf("DIRT", "GRASS_BLOCK", "COARSE_DIRT", "PODZOL", "SAND", "RED_SAND", "GRAVEL", "CLAY", "MUD", "SOUL_SAND", "SOUL_SOIL")
            .associate { "break:$it" to 0.05 }
        val farmer = listOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "COCOA", "PUMPKIN", "MELON", "SWEET_BERRY_BUSH").associate { "harvest:$it" to 0.2 } +
            listOf("COW", "SHEEP", "PIG", "CHICKEN", "RABBIT", "GOAT").associate { "breed:$it" to 1.0 }
        val hunter = mapOf(
            "ZOMBIE" to 1.0, "HUSK" to 1.0, "DROWNED" to 1.0, "ZOMBIE_VILLAGER" to 1.0, "SKELETON" to 1.0, "STRAY" to 1.0, "SPIDER" to 1.0,
            "CAVE_SPIDER" to 1.0, "CREEPER" to 1.5, "WITCH" to 2.0, "PHANTOM" to 2.0, "PILLAGER" to 2.0, "SLIME" to 0.5, "MAGMA_CUBE" to 0.5,
            "ENDERMAN" to 3.0, "BLAZE" to 3.0, "GHAST" to 3.0, "GUARDIAN" to 3.0, "SHULKER" to 3.0, "VINDICATOR" to 3.0, "PIGLIN_BRUTE" to 4.0,
            "EVOKER" to 5.0, "WITHER_SKELETON" to 6.0, "ELDER_GUARDIAN" to 20.0, "ENDER_DRAGON" to 100.0,
        ).mapKeys { "kill:${it.key}" }
        val fisher = mapOf("fish:FISH" to 2.0, "fish:TREASURE" to 8.0, "fish:JUNK" to 0.0)
        return listOf(
            Triple("holzfaeller", "Holzfäller" to "IRON_AXE", logs),
            Triple("miner", "Miner" to "IRON_PICKAXE", miner),
            Triple("graeber", "Gräber" to "IRON_SHOVEL", digger),
            Triple("farmer", "Farmer" to "IRON_HOE", farmer),
            Triple("jaeger", "Jäger" to "BOW", hunter),
            Triple("fischer", "Fischer" to "FISHING_ROD", fisher),
        ).mapIndexed { i, (id, meta, actions) ->
            ConfigDocument(
                id,
                i,
                obj(
                    "name" to meta.first,
                    "icon" to meta.second,
                    "villager" to "henni_hacke",
                    "worlds" to farmWorlds,
                    "plots" to (id == "farmer"),
                    "actions" to JsonObject(actions.mapValues { JsonPrimitive(it.value) }),
                ),
            )
        }
    }

    private fun kits(): List<ConfigDocument> {
        /** One material entry of a kit. */
        fun m(material: String, amount: Int = 1) = obj("type" to "material", "material" to material, "amount" to amount)
        return listOf(
            ConfigDocument(
                "starter", 0,
                obj(
                    "name" to "Starter", "icon" to "STONE_PICKAXE", "cooldownSeconds" to 0, "once" to true, "minTier" to 0,
                    "items" to JsonArray(listOf(m("STONE_PICKAXE"), m("STONE_AXE"), m("STONE_SHOVEL"), m("STONE_SWORD"), m("STONE_HOE"), m("BREAD", 16), m("TORCH", 32), m("RED_BED"))),
                ),
            ),
            ConfigDocument(
                "taeglich", 1,
                obj(
                    "name" to "Täglich", "icon" to "COOKED_BEEF", "cooldownSeconds" to 86_400, "once" to false, "minTier" to 0,
                    "items" to JsonArray(listOf(m("COOKED_BEEF", 8), m("TORCH", 16), m("IRON_PICKAXE"))),
                ),
            ),
            ConfigDocument(
                "woechentlich", 2,
                obj(
                    "name" to "Wöchentlich", "icon" to "IRON_CHESTPLATE", "cooldownSeconds" to 604_800, "once" to false, "minTier" to 1,
                    "items" to JsonArray(listOf(m("IRON_HELMET"), m("IRON_CHESTPLATE"), m("IRON_LEGGINGS"), m("IRON_BOOTS"), m("IRON_INGOT", 32), m("GOLDEN_APPLE", 4))),
                ),
            ),
            ConfigDocument(
                "baumeister", 3,
                obj(
                    "name" to "Baumeister", "icon" to "BRICKS", "cooldownSeconds" to 604_800, "once" to false, "minTier" to 2,
                    "items" to JsonArray(
                        listOf(
                            obj(
                                "type" to "choice", "picks" to 4, "amount" to 64,
                                "options" to listOf("STONE_BRICKS", "BRICKS", "SMOOTH_STONE", "QUARTZ_BLOCK", "OAK_PLANKS", "SPRUCE_PLANKS", "DEEPSLATE_BRICKS", "WHITE_CONCRETE", "GLASS"),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun rewards(): List<ConfigDocument> {
        val days = listOf(100L, 150L, 200L, 300L, 400L, 500L, 1000L).mapIndexed { i, kronen ->
            val keys = if (i == 6) JsonArray(listOf(obj("crate" to "standard", "amount" to 1))) else JsonArray(emptyList())
            ConfigDocument("day${i + 1}", i, obj("kronen" to kronen, "items" to JsonArray(emptyList()), "keys" to keys))
        }
        val milestones = listOf(10 to 500L, 25 to 2000L, 50 to 10_000L).mapIndexed { i, (level, kronen) ->
            ConfigDocument("job_$level", 10 + i, obj("kronen" to kronen, "items" to JsonArray(emptyList()), "keys" to JsonArray(emptyList())))
        }
        return days + milestones + ConfigDocument("tutorial", 20, obj("kronen" to 500L, "items" to JsonArray(emptyList()), "keys" to JsonArray(emptyList())))
    }

    private fun limits(): List<ConfigDocument> = listOf(
        listOf(2, 2, 10, 5, 90, 0, 0, 0),
        listOf(3, 4, 20, 8, 90, 1, 1, 0),
        listOf(5, 7, 35, 12, 180, 1, 1, 1),
        listOf(8, 12, 60, 20, 180, 1, 1, 1),
    ).mapIndexed { tier, v ->
        ConfigDocument(
            "tier$tier",
            tier,
            obj(
                "plots" to v[0], "homes" to v[1], "shops" to v[2], "auctions" to v[3], "inactivityDays" to v[4],
                "back" to (v[5] == 1), "weeklyKit" to (v[6] == 1), "builderKit" to (v[7] == 1),
            ),
        )
    }

    private fun villagers(): List<ConfigDocument> = listOf(
        listOf("lotte_laterne", "Lotte Laterne", "guide", "spawn", "Himmelblau", "Laterne an langem Stab", "Da drüben! Nein, das andere Drüben."),
        listOf("gundula_grundmann", "Gundula Grundmann", "plots", "rathaus", "Schiefergrau", "Maßband, dicker Foliant, Stempel", "Grundstück 4-17. Gestempelt. Zweifach."),
        listOf("fritz_feilbiet", "Fritz Feilbiet", "adminshop", "markt", "Kürbisorange mit Schürze", "Waage, Bleistift hinterm Ohr", "Frische Steine! Heute noch steiniger!"),
        listOf("ludwig_lot", "Ludwig Lot", "auction", "auktionshalle", "Dunkelgrün mit Weste", "Auktionshammer", "Zum Zweiten … und verkauft an den Herrn mit dem Hut!"),
        listOf("henni_hacke", "Henni Hacke", "jobs", "portalplatz", "Erdrot", "Spitzhacke über der Schulter", "Reden später. Erst Schicht."),
        listOf("waldemar_moos", "Waldemar Moos", "farm", "portalplatz", "Moosgrün", "Axt, Vogelnest auf dem Hut", "Sonntag wächst alles neu. Der Wald verzeiht."),
        listOf("rupert_ranzen", "Rupert Ranzen", "kits", "rathaus_keller", "Ockerbraun", "Riesiger Rucksack, Klemmbrett", "Drei Fackeln fehlen. Ich zähle nochmal."),
        listOf("paula_paket", "Paula Paket", "reward", "markt", "Postgelb mit roten Säumen", "Umhängetasche voller Briefe", "Für dich! Unterschreib – nein, keine Zeit!"),
        listOf("brunhilde_brecher", "Brunhilde Brecher", "arena", "arenator", "Eisengrau mit Rot", "Rundschild, Holzschwert", "Hinfallen ist erlaubt. Liegenbleiben nicht."),
        listOf("greta_goldzahn", "Greta Goldzahn", "crates", "schatzkammer", "Purpurbraun mit Goldknöpfen", "Goldzahn, Schlüsselring", "Psst. In der dritten Kiste … nein, ich sag nichts."),
    ).mapIndexed { i, v ->
        ConfigDocument(v[0], i, obj("name" to v[1], "role" to v[2], "location" to v[3], "robe" to v[4], "prop" to v[5], "quote" to v[6]))
    }

    private fun enchants(): List<ConfigDocument> = listOf(
        listOf("erntesegen", "Erntesegen", "HOE", "double_harvest_chance", 0.10, 3),
        listOf("jobglueck", "Jobglück", "TOOL,WEAPON", "job_payout_bonus", 0.05, 5),
        listOf("erfahrungsdurst", "Erfahrungsdurst", "TOOL,WEAPON", "xp_bonus", 0.15, 3),
        listOf("lebensraub", "Lebensraub", "WEAPON", "lifesteal", 0.03, 3),
        listOf("schatzsucher", "Schatzsucher", "FISHING_ROD", "treasure_chance", 0.05, 3),
        listOf("federtritt", "Federtritt", "BOOTS", "fall_damage_reduction", 0.20, 4),
    ).mapIndexed { i, v ->
        ConfigDocument(
            v[0] as String,
            i,
            obj("name" to v[1], "appliesTo" to (v[2] as String).split(','), "effect" to v[3], "perLevel" to v[4], "suggestedMax" to v[5]),
        )
    }

    private fun abilities(): List<ConfigDocument> = listOf(
        "flaechenabbau" to obj("name" to "Flächenabbau", "appliesTo" to listOf("PICKAXE", "SHOVEL"), "params" to obj("size" to 3, "sizes" to listOf(3, 5), "depth" to 1, "maxDepth" to 3)),
        "aderabbau" to obj("name" to "Aderabbau", "appliesTo" to listOf("PICKAXE"), "params" to obj("maxBlocks" to 32, "oresOnly" to true)),
        "baumfaeller" to obj("name" to "Baumfäller", "appliesTo" to listOf("AXE"), "params" to obj("maxBlocks" to 128, "leaves" to false)),
        "autoschmelzen" to obj("name" to "Auto-Schmelzen", "appliesTo" to listOf("TOOL"), "params" to obj()),
        "telekinese" to obj("name" to "Telekinese", "appliesTo" to listOf("TOOL", "WEAPON"), "params" to obj()),
        "erntekreis" to obj("name" to "Erntekreis", "appliesTo" to listOf("HOE"), "params" to obj("radius" to 1, "maxRadius" to 3, "replant" to true)),
        "magnet" to obj("name" to "Magnet", "appliesTo" to listOf("ANY"), "params" to obj("radius" to 5, "minRadius" to 3, "maxRadius" to 10)),
        "trankaura" to obj(
            "name" to "Trank-Aura", "appliesTo" to listOf("ANY"),
            "params" to obj(
                "effect" to "HASTE", "level" to 1, "maxLevel" to 5, "mode" to "HELD",
                "effects" to listOf("HASTE", "SPEED", "NIGHT_VISION", "FIRE_RESISTANCE", "WATER_BREATHING", "JUMP_BOOST", "STRENGTH", "RESISTANCE", "REGENERATION"),
            ),
        ),
        "seelenbindung" to obj("name" to "Seelenbindung", "appliesTo" to listOf("ANY"), "params" to obj()),
    ).mapIndexed { i, (id, body) ->
        val multiBlock = id in setOf("flaechenabbau", "aderabbau", "baumfaeller", "erntekreis")
        ConfigDocument(id, i, JsonObject(body + ("multiBlock" to JsonPrimitive(multiBlock)) + ("disabledWorlds" to JsonArray(if (multiBlock) listOf(JsonPrimitive("spawn")) else emptyList()))))
    }

    private fun crates(): List<ConfigDocument> {
        /** One crate entry. */
        fun e(id: String, type: String, ref: String, amount: Long, weight: Int, rarity: String) =
            obj("id" to id, "type" to type, "ref" to ref, "amount" to amount, "weight" to weight, "rarity" to rarity)
        return listOf(
            ConfigDocument(
                "standard",
                0,
                obj(
                    "name" to "Standardkiste", "rarity" to "NORMAL", "look" to "CHEST", "key" to "key_standard", "announceFrom" to "TITAN",
                    "entries" to JsonArray(
                        listOf(
                            e("kronen_100", "kronen", "", 100, 40, "NORMAL"),
                            e("xp", "material", "EXPERIENCE_BOTTLE", 16, 15, "NORMAL"),
                            e("apples", "material", "GOLDEN_APPLE", 2, 15, "NORMAL"),
                            e("kronen_500", "kronen", "", 500, 15, "SPECIAL"),
                            e("diamonds", "material", "DIAMOND", 3, 10, "SPECIAL"),
                            e("netherite", "material", "NETHERITE_INGOT", 1, 2, "ELITE"),
                            e("notch", "material", "ENCHANTED_GOLDEN_APPLE", 1, 2, "TITAN"),
                            e("kronen_5000", "kronen", "", 5000, 1, "BOSS"),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to element(v) })

    private fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { element(it) })
        else -> JsonPrimitive(value.toString())
    }
}
