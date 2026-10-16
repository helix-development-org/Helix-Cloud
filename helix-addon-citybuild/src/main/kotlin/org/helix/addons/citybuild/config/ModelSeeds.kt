package org.helix.addons.citybuild.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Seeds of the model catalog (game concept A21): collection `models` (every item, furniture, vehicle, pet,
 * livestock, creature, train and station of the V1 catalog) and collection `recipes` (stove recipes).
 *
 * `models` document body (contract with the Paper plugin, package `de.tytoss.citybuild.model`): `kind`
 * (`item`, `hat`, `food`, `material`, `furniture`, `vehicle`, `pet`, `livestock`, `creature`, `train`, `station`),
 * `name`, optional `itemModel`/`rig`, `variants`, `price` (Kronen or `null`), `dealer` (`moebel`, `stellmacher`,
 * `tiere` or `null`) and a kind specific `data` object:
 * - `item`: `base` (vanilla material giving tool behaviour).
 * - `hat`: `equip` (`slot` head/chest, optional `asset`, `armor`).
 * - `food`: `food` (`nutrition`, `saturation`, `seconds`, `drink`, `effects`).
 * - `furniture`: `function` (`none`, `seat`, `light`, `storage`, `stove`), `width`, `height` (hitbox in blocks),
 *   `collision`, `seatHeight`, `light` (0–15), `rows` (storage rows).
 * - `vehicle`: `type` (`land`, `water`, `air`), `speed` (blocks/s), `seats`, `step`, `maxY`.
 * - `pet`: `traits` (`cosmetic`, `care`, `ability`), `favoriteFood`, `abilities`, `tricks`.
 * - `livestock`: `base` (vanilla mob), `yields` (`model`, `amount`), `intervalMinutes`, `feed`, `placement`.
 * - `creature`: `base`, `worlds`, `biomes`, `night`, `health`, `damage`, `speed`, `drops`, `hunter`, `boss`.
 * - `train`: `role` (`locomotive`, `wagon`), `length`.
 * - `station`: empty.
 *
 * `recipes` document body: `inputs` (list of `{model|material, amount}`), `output` (`{model|material, amount}`),
 * `seconds`.
 */
object ModelSeeds {
    /**
     * @return the seed documents of collection `models`.
     */
    fun models(): List<ConfigDocument> {
        val list = mutableListOf<Pair<String, JsonObject>>()
        /** Adds one catalog entry. */
        fun add(id: String, kind: String, name: String, price: Long?, dealer: String?, data: JsonObject = obj(), variants: List<String> = emptyList()) {
            list += id to obj("kind" to kind, "name" to name, "variants" to variants, "price" to price, "dealer" to dealer, "data" to data)
        }
        // Werkzeuge & Waffen (Schmiede/Kisten, kein Händler)
        listOf(
            Triple("kronland_hammer", "Kronland-Hammer", "IRON_AXE"), Triple("sense", "Sense", "IRON_HOE"),
            Triple("laternenstab", "Laternenstab", "STICK"), Triple("saebel", "Säbel", "IRON_SWORD"),
            Triple("spitzhacke", "Kronland-Spitzhacke", "IRON_PICKAXE"), Triple("axt", "Kronland-Axt", "IRON_AXE"),
            Triple("angel", "Kronland-Angel", "FISHING_ROD"), Triple("schaufel", "Kronland-Schaufel", "IRON_SHOVEL"),
        ).forEach { (id, name, base) -> add(id, "item", name, null, null, obj("base" to base)) }
        // Hüte & Kostüme (Möbelhändlerin)
        listOf(
            listOf("zylinder", "Zylinder", 1500L, "head"), listOf("strohhut", "Strohhut", 500L, "head"),
            listOf("krone", "Krone", 25000L, "head"), listOf("kochmuetze", "Kochmütze", 800L, "head"),
            listOf("hexenhut", "Hexenhut", 2500L, "head"), listOf("ritterhelm", "Ritterhelm", 4000L, "head"),
            listOf("umhang", "Umhang", 3000L, "chest"), listOf("rucksack", "Rucksack", 2000L, "chest"),
        ).forEach { v ->
            val slot = v[3] as String
            val equip = if (slot == "chest") obj("slot" to slot, "asset" to "citybuild:${v[0]}", "armor" to 0) else obj("slot" to slot, "armor" to if (v[0] == "ritterhelm") 2 else 0)
            add(v[0] as String, "hat", v[1] as String, v[2] as Long, "moebel", obj("equip" to equip))
        }
        // Essen
        /** Food component data. */
        fun food(nutrition: Int, saturation: Double, seconds: Double = 1.6, effects: List<JsonObject> = emptyList(), drink: Boolean = false) =
            obj("food" to obj("nutrition" to nutrition, "saturation" to saturation, "seconds" to seconds, "drink" to drink, "effects" to effects))
        /** One consume effect. */
        fun effect(type: String, seconds: Int, amplifier: Int = 0, chance: Double = 1.0) = obj("type" to type, "seconds" to seconds, "amplifier" to amplifier, "chance" to chance)
        add("brezel", "food", "Brezel", 30, "tiere", food(5, 6.0))
        add("kuchenstueck", "food", "Kuchenstück", 60, "tiere", food(4, 3.0, 1.2, listOf(effect("speed", 20))))
        add("apfelstrudel", "food", "Apfelstrudel", null, null, food(8, 10.0, 2.0, listOf(effect("regeneration", 8))))
        add("eintopf", "food", "Eintopf", null, null, food(10, 12.0, 2.4, listOf(effect("resistance", 30))))
        add("kaese", "food", "Käse", 40, "tiere", food(4, 5.0))
        add("honigbrot", "food", "Honigbrot", null, null, food(7, 9.0, 1.6, listOf(effect("haste", 60))))
        add("kronland_ei_gekocht", "food", "Gekochtes Kronland-Ei", null, null, food(4, 6.0, 1.0))
        add("pilzpfanne", "food", "Pilzpfanne", null, null, food(9, 11.0, 2.0, listOf(effect("night_vision", 90))))
        // Rohstoffe (aus Tieren, Kreaturen, Jobs)
        add("wolle", "material", "Wolle", null, null, obj(), listOf("weiss", "grau", "braun", "schwarz", "rosa"))
        listOf(
            "federn" to "Federn", "kronland_ei" to "Kronland-Ei", "milchkanne" to "Milchkanne", "honigwabe" to "Honigwabe",
            "pilz" to "Kronland-Pilz", "ruebe" to "Rübe", "glutstaub" to "Glutstaub", "nebelfell" to "Nebelfell", "schattenperle" to "Schattenperle",
        ).forEach { (id, name) -> add(id, "material", name, null, null) }
        // Möbel & Deko (Möbelhändlerin)
        /** Furniture data. */
        fun f(function: String, width: Double = 1.0, height: Double = 1.0, collision: Boolean = false, extra: List<Pair<String, Any?>> = emptyList()) =
            obj("function" to function, "width" to width, "height" to height, "collision" to collision, *extra.toTypedArray())
        add("stuhl", "furniture", "Stuhl", 150, "moebel", f("seat", 0.8, 1.2, extra = listOf("seatHeight" to 0.45)))
        add("bank", "furniture", "Bank", 250, "moebel", f("seat", 1.8, 1.0, extra = listOf("seatHeight" to 0.45)))
        add("hocker", "furniture", "Hocker", 100, "moebel", f("seat", 0.6, 0.6, extra = listOf("seatHeight" to 0.4)))
        add("tisch_rund", "furniture", "Runder Tisch", 300, "moebel", f("none", 1.0, 0.9, true))
        add("tisch_eckig", "furniture", "Eckiger Tisch", 300, "moebel", f("none", 1.0, 0.9, true))
        add("laterne", "furniture", "Laterne", 200, "moebel", f("light", 0.5, 0.8, extra = listOf("light" to 15)))
        add("stehlampe", "furniture", "Stehlampe", 400, "moebel", f("light", 0.6, 2.0, true, listOf("light" to 14)))
        add("regal", "furniture", "Regal", 350, "moebel", f("storage", 1.0, 2.0, true, listOf("rows" to 3)))
        add("fass", "furniture", "Fass", 250, "moebel", f("storage", 0.9, 1.0, true, listOf("rows" to 2)))
        add("truhe", "furniture", "Kronland-Truhe", 500, "moebel", f("storage", 1.0, 0.9, true, listOf("rows" to 3)))
        listOf("rot", "gelb", "blau").forEach { c -> add("blumentopf_$c", "furniture", "Blumentopf ($c)", 80, "moebel", f("none", 0.5, 0.7)) }
        add("wegweiser", "furniture", "Wegweiser", 150, "moebel", f("none", 0.6, 2.0, true))
        add("wanduhr", "furniture", "Wanduhr", 600, "moebel", f("none", 0.8, 0.8))
        add("teppich", "furniture", "Teppich", 200, "moebel", f("none", 2.0, 0.1))
        add("marktstand", "furniture", "Marktstand", 2500, "moebel", f("none", 2.0, 2.5, true))
        add("kochherd", "furniture", "Kochherd", 1500, "moebel", f("stove", 1.0, 1.0, true))
        add("brunnen_klein", "furniture", "Kleiner Brunnen", 2000, "moebel", f("none", 1.8, 1.6, true))
        add("gartenzaun", "furniture", "Gartenzaun", 60, "moebel", f("none", 1.0, 1.0, true))
        add("vogelhaus", "furniture", "Vogelhaus", 300, "moebel", f("none", 0.6, 1.8))
        // Fahrzeuge (Stellmacher Kuno Kurbel)
        /** Vehicle data. */
        fun v(type: String, speed: Double, seats: Int, maxY: Int = 200) = obj("type" to type, "speed" to speed, "seats" to seats, "step" to 1, "maxY" to maxY)
        add("handkarren", "vehicle", "Handkarren", 2000, "stellmacher", v("land", 4.0, 1), listOf("holz", "rot"))
        add("laufrad", "vehicle", "Laufrad", 3500, "stellmacher", v("land", 7.0, 1), listOf("holz", "blau"))
        add("pferdekutsche", "vehicle", "Pferdekutsche", 25000, "stellmacher", v("land", 9.0, 4), listOf("rot", "gruen", "gold"))
        add("bauernwagen", "vehicle", "Bauernwagen", 8000, "stellmacher", v("land", 6.0, 2), listOf("holz", "gruen"))
        add("floss", "vehicle", "Floß", 1500, "stellmacher", v("water", 4.0, 2))
        add("ruderboot", "vehicle", "Ruderboot", 6000, "stellmacher", v("water", 6.0, 2), listOf("holz", "blau"))
        add("segelboot", "vehicle", "Segelboot", 30000, "stellmacher", v("water", 10.0, 4), listOf("weiss", "rot"))
        add("heissluftballon", "vehicle", "Heißluftballon", 40000, "stellmacher", v("air", 4.0, 3), listOf("rot", "blau", "gold"))
        add("flugmaschine", "vehicle", "Flugmaschine", 60000, "stellmacher", v("air", 12.0, 1))
        add("greif", "vehicle", "Greif", null, null, v("air", 10.0, 2))
        // Haustiere (Tierhändlerin Frieda Feder)
        /** Pet data. */
        fun p(traits: List<String>, favorite: String, abilities: List<String> = emptyList()) =
            obj("traits" to traits, "favoriteFood" to favorite, "abilities" to abilities, "tricks" to listOf("huepfen", "drehen", "pfote"))
        add("katze", "pet", "Katze", 5000, "tiere", p(listOf("cosmetic", "care"), "kaese"))
        add("hund", "pet", "Hund", 5000, "tiere", p(listOf("cosmetic", "care"), "brezel"))
        add("igel", "pet", "Igel", 4000, "tiere", p(listOf("cosmetic", "care"), "pilz"))
        add("fuchs", "pet", "Fuchs", 9000, "tiere", p(listOf("care", "ability"), "kronland_ei", listOf("magnet")))
        add("eule", "pet", "Eule", 9000, "tiere", p(listOf("care", "ability"), "kuchenstueck", listOf("night_vision")))
        add("schildkroete", "pet", "Schildkröte", 3000, "tiere", p(listOf("cosmetic"), "ruebe"))
        add("frosch", "pet", "Frosch", 3000, "tiere", p(listOf("cosmetic"), "honigbrot"))
        add("mini_drache", "pet", "Mini-Drache", null, null, p(listOf("care", "ability"), "pilzpfanne", listOf("light", "speed")))
        // Nutztiere (Tierhändlerin)
        /** Livestock data. */
        fun l(base: String, yields: List<Pair<String, Int>>, minutes: Int, feed: String, placement: String = "mob") =
            obj("base" to base, "yields" to yields.map { (m, n) -> obj("model" to m, "amount" to n) }, "intervalMinutes" to minutes, "feed" to feed, "placement" to placement)
        add("kronland_huhn", "livestock", "Kronland-Huhn", 400, "tiere", l("CHICKEN", listOf("kronland_ei" to 1, "federn" to 2), 20, "WHEAT_SEEDS"))
        add("wollschaf", "livestock", "Wollschaf", 800, "tiere", l("SHEEP", listOf("wolle" to 2), 30, "WHEAT"))
        add("fleckkuh", "livestock", "Fleckkuh", 1000, "tiere", l("COW", listOf("milchkanne" to 1), 30, "WHEAT"))
        add("bienenkorb", "livestock", "Bienenkorb", 1500, "tiere", l("BEE", listOf("honigwabe" to 1), 45, "DANDELION", "block"))
        // Farmwelt-Kreaturen und Boss
        /** Creature data. */
        fun c(base: String, worlds: List<String>, biomes: List<String>, health: Double, damage: Double, drops: List<Triple<String, Int, Double>>, hunter: Double, night: Boolean = false, boss: Boolean = false) =
            obj(
                "base" to base, "worlds" to worlds, "biomes" to biomes, "night" to night, "health" to health, "damage" to damage, "speed" to 0.25,
                "drops" to drops.map { (m, max, chance) -> obj("model" to m, "min" to 1, "max" to max, "chance" to chance) }, "hunter" to hunter, "boss" to boss,
            )
        add("pilzling", "creature", "Pilzling", null, null, c("ZOMBIE", listOf("farm"), listOf("FOREST", "DARK_FOREST", "BIRCH_FOREST"), 16.0, 3.0, listOf(Triple("pilz", 2, 0.8)), 1.0))
        add("moorwicht", "creature", "Moorwicht", null, null, c("ZOMBIE", listOf("farm"), listOf("SWAMP", "MANGROVE_SWAMP"), 22.0, 4.0, listOf(Triple("nebelfell", 1, 0.6)), 2.0))
        add("steinkrabbler", "creature", "Steinkrabbler", null, null, c("SPIDER", listOf("farm"), listOf("CAVES", "DRIPSTONE_CAVES", "LUSH_CAVES"), 20.0, 4.0, listOf(Triple("glutstaub", 2, 0.6)), 2.0))
        add("nebelwolf", "creature", "Nebelwolf", null, null, c("WOLF", listOf("farm"), emptyList(), 24.0, 5.0, listOf(Triple("nebelfell", 2, 0.7), Triple("federn", 2, 0.3)), 3.0, night = true))
        add("glutkaefer", "creature", "Glutkäfer", null, null, c("SPIDER", listOf("farm_nether"), emptyList(), 26.0, 6.0, listOf(Triple("glutstaub", 3, 0.8)), 3.0))
        add("endschatten", "creature", "Endschatten", null, null, c("ENDERMAN", listOf("farm_end"), emptyList(), 40.0, 7.0, listOf(Triple("schattenperle", 1, 0.5)), 6.0))
        add("waldriese", "creature", "Waldriese", null, null, c("ZOMBIE", listOf("farm"), emptyList(), 1200.0, 12.0, listOf(Triple("schattenperle", 3, 1.0)), 50.0, boss = true))
        // Bahn
        add("dampflok", "train", "Dampflok", null, null, obj("role" to "locomotive", "length" to 5))
        add("personenwagen", "train", "Personenwagen", null, null, obj("role" to "wagon", "length" to 5))
        add("gueterwagen", "train", "Güterwagen", null, null, obj("role" to "wagon", "length" to 4))
        add("kohlewagen", "train", "Kohlewagen", null, null, obj("role" to "wagon", "length" to 3))
        add("haltestelle", "station", "Haltestelle", null, null)
        return list.mapIndexed { i, (id, body) -> ConfigDocument(id, i, body) }
    }

    /**
     * @return the seed documents of collection `recipes` (stove).
     */
    fun recipes(): List<ConfigDocument> {
        /** One recipe ingredient or result. */
        fun item(model: String? = null, material: String? = null, amount: Int = 1) =
            obj("model" to model, "material" to material, "amount" to amount)
        return listOf(
            "brezel" to obj("inputs" to listOf(item(material = "WHEAT", amount = 3)), "output" to item("brezel"), "seconds" to 5),
            "kuchenstueck" to obj("inputs" to listOf(item(material = "WHEAT", amount = 2), item("milchkanne"), item("kronland_ei")), "output" to item("kuchenstueck", amount = 4), "seconds" to 10),
            "apfelstrudel" to obj("inputs" to listOf(item(material = "APPLE", amount = 3), item(material = "WHEAT", amount = 2), item("honigwabe")), "output" to item("apfelstrudel", amount = 2), "seconds" to 10),
            "eintopf" to obj("inputs" to listOf(item("ruebe", amount = 2), item(material = "POTATO", amount = 2), item(material = "BEEF")), "output" to item("eintopf"), "seconds" to 12),
            "kaese" to obj("inputs" to listOf(item("milchkanne", amount = 2)), "output" to item("kaese", amount = 2), "seconds" to 8),
            "honigbrot" to obj("inputs" to listOf(item(material = "BREAD"), item("honigwabe")), "output" to item("honigbrot", amount = 2), "seconds" to 5),
            "kronland_ei_gekocht" to obj("inputs" to listOf(item("kronland_ei")), "output" to item("kronland_ei_gekocht"), "seconds" to 4),
            "pilzpfanne" to obj("inputs" to listOf(item("pilz", amount = 3), item("kronland_ei")), "output" to item("pilzpfanne"), "seconds" to 8),
        ).mapIndexed { i, (id, body) -> ConfigDocument(id, i, body) }
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
