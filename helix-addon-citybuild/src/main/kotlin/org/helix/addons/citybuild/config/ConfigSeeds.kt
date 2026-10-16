package org.helix.addons.citybuild.config

/**
 * Definition of one typed setting: key, value type, default and a human readable purpose.
 *
 * @property key setting key without namespace, for example `plots.mergePrice`.
 * @property type value type (`INT`, `LONG`, `DOUBLE`, `BOOL`, `STRING`, `INT_LIST`, `STRING_LIST`).
 * @property default default value rendered as text.
 * @property description purpose shown in the dashboard and the in-game admin hub.
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
            "LONG" -> require(value.toLongOrNull() != null) { "'$value' is not a LONG" }
            "DOUBLE" -> require(value.toDoubleOrNull() != null) { "'$value' is not a DOUBLE" }
            "BOOL" -> require(value.equals("true", true) || value.equals("false", true)) { "'$value' is not a BOOL" }
            "INT_LIST" -> require(value.isEmpty() || value.split(',').all { it.trim().toIntOrNull() != null }) { "'$value' is not an INT_LIST" }
        }
        return if (type == "BOOL") value.lowercase() else value
    }
}

/**
 * Initial CityBuild configuration: every default number of the game concept
 * (`CityBuild/CityBuild-Spielkonzept.md`). Seeded into the config store when a key or document is
 * missing; afterwards the store is the single source of truth and the in-game admin hub edits it.
 */
object ConfigSeeds {
    /** Task name of the CityBuild servers (document id in the `servers` collection). */
    const val TASK: String = "CityBuild"

    /** Service ids of the three city instances. */
    val INSTANCES: List<String> = listOf("CityBuild-1", "CityBuild-2", "CityBuild-3")

    /** All typed settings with defaults. */
    val settings: List<SettingDefinition> = buildList {
        /** Adds an INT setting. */
        fun int(k: String, v: Int, d: String) = add(SettingDefinition(k, "INT", v.toString(), d))
        /** Adds a LONG setting. */
        fun long(k: String, v: Long, d: String) = add(SettingDefinition(k, "LONG", v.toString(), d))
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

        str("general.zone", "Europe/Berlin", "Time zone of calendar days (daily reward, daily limits, farm reset)")
        str("general.task", TASK, "Task whose services are the city instances")

        // A3 plots
        int("plots.size", 48, "Plot edge length in blocks")
        int("plots.road", 7, "Road width in blocks")
        int("plots.groundHeight", 64, "Height of the plot ground")
        ints("plots.prices", listOf(0, 5000, 12000, 20000, 30000, 45000, 60000, 80000, 100000), "Price of the n-th plot per instance (index 0 = first plot); beyond the list the last price applies")
        long("plots.mergePrice", 2500, "Kronen per merge of two adjacent own plots")
        long("plots.clearPrice", 500, "Kronen to clear a plot")
        long("plots.biomePrice", 1000, "Kronen to change the biome of a plot")
        int("plots.maxAnimals", 30, "Animals per plot")
        int("plotmarket.maxOffers", 3, "Simultaneous plot market offers per player and instance")

        // A5 fees (per mille)
        int("fees.auctionListingPermille", 20, "Auction listing fee in ‰ of the price")
        long("fees.auctionListingMin", 10, "Minimum auction listing fee")
        int("fees.auctionSalePermille", 50, "Auction sale fee in ‰ (paid by the seller)")
        int("fees.shopPermille", 30, "Player shop fee in ‰ of the turnover")
        int("fees.plotMarketPermille", 50, "Plot market fee in ‰ of the price")
        int("fees.tradePermille", 20, "/trade fee in ‰ of the transferred Kronen")

        // A6 admin shop
        long("adminshop.dailySellCap", 3000, "Kronen a player may earn per day by selling to the merchant (network-wide)")
        int("adminshop.defaultItemLimit", 512, "Items per ware and day a player may sell to the merchant")

        // A8 jobs
        int("jobs.maxActive", 2, "Active jobs per player")
        int("jobs.switchCooldownHours", 24, "Cooldown per job slot after a switch")
        long("jobs.dailyCap", 4000, "Kronen a player may earn per day through jobs (network-wide)")
        int("jobs.maxLevel", 50, "Highest job level")
        int("jobs.xpPerKrone", 10, "Job XP per Krone of base payout")
        int("jobs.levelBase", 100, "XP curve: XP needed for level n = levelBase × n^levelExponent")
        dbl("jobs.levelExponent", 1.6, "XP curve exponent")
        int("jobs.bonusPercentPerLevel", 2, "Additional payout in % per job level")
        dbl("jobs.multiblockFactor", 0.5, "Payout factor for extra blocks of multi-block abilities")
        int("jobs.payoutIntervalSeconds", 30, "Interval of batched job payouts")
        ints("jobs.milestones", listOf(10, 25, 50), "Job levels with a milestone reward (rewards documents job_<level>)")

        // A9 auction house
        ints("auction.durationsHours", listOf(12, 24, 48), "Selectable auction durations")
        long("auction.minPrice", 1, "Lowest auction price")
        long("auction.maxPrice", 10_000_000, "Highest auction price")

        // A11 comfort
        int("teleport.warmupSeconds", 3, "Warm-up of home/warp/spawn/tpa teleports (no movement, no damage)")
        int("teleport.cooldownSeconds", 30, "Cooldown between teleports")
        int("tpa.timeoutSeconds", 60, "Validity of a teleport request")
        strs("homes.allowedWorlds", listOf("plots", "spawn"), "Worlds in which homes may be set")
        int("trade.requestSeconds", 60, "Validity of a trade request")
        int("trade.maxDistance", 20, "Maximum distance of both trade partners")
        int("trade.countdownSeconds", 3, "Countdown after both partners are ready")
        int("trade.slots", 12, "Offer slots per side")
        bool("arena.keepInventory", true, "Keep inventory and XP on death in the arena")

        // A4 farm worlds
        str("farm.resetDay", "SUNDAY", "Weekday of the farm world reset")
        str("farm.resetTime", "04:00", "Time of the farm world reset")
        ints("farm.countdownMinutes", listOf(60, 30, 15, 5, 1), "Countdown announcements before the reset")
        int("farm.countdownFinalSeconds", 10, "Final seconds countdown before the reset")
        int("farm.borderOverworld", 10000, "World border diameter of farm")
        int("farm.borderNether", 5000, "World border diameter of farm_nether")
        int("farm.borderEnd", 6000, "World border diameter of farm_end")
        int("farm.rtpRadius", 2000, "Random teleport radius around the farm spawn")
        int("farm.rtpCooldownSeconds", 30, "Cooldown of the farm world teleport")
        int("farm.pregenRadius", 500, "Radius pre-generated after a reset")

        // A15 tutorial, A11 reward
        int("reward.streakDays", 7, "Length of the daily reward streak")

        // A16 forge
        int("forge.maxEnchantLevel", 255, "Highest enchantment level the forge allows")
        int("forge.maxLoreLines", 10, "Lore lines per forged item")
        int("crates.animationTicks", 60, "Duration of the crate opening animation")
        int("forge.ability.hardMax", 256, "Upper bound of blocks a single multi-block ability may break")

        // A2 spawn town
        bool("spawn.placeholder", true, "Build the placeholder market square while no spawn map is installed")

        // A12 inactivity
        int("plots.inactivityDays", 90, "Days of absence after which plots are released (tier0/tier1)")
        int("plots.inactivityDaysPremium", 180, "Days of absence after which plots are released (tier2/tier3)")
    }

    /** Defaults replaced by a later release; an untouched old default is lifted on load. */
    val supersededDefaults: Map<String, Set<String>> = emptyMap()

    /** Seed documents per collection. */
    val collections: Map<String, List<ConfigDocument>> by lazy { SeedCollections.all() }
}
