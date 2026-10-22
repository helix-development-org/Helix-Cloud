package org.helix.node.release

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Settings of the network release reset, stored in `config/release.json` (written with the defaults on first use
 * so operators can adapt them).
 *
 * @property phrase confirmation phrase `release.reset run` must be given.
 * @property tasks game tasks to stop during the reset and the workspace paths to wipe per static service.
 */
@Serializable
data class ReleaseConfig(
    val phrase: String = DEFAULT_PHRASE,
    val tasks: List<ReleaseTask> = DEFAULT_TASKS,
) {
    /** Defaults and file handling. */
    companion object {
        /** Default confirmation phrase. */
        const val DEFAULT_PHRASE: String = "KRONLAND-RELEASE"

        /** File name below the node's config directory. */
        const val FILE_NAME: String = "release.json"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        /** Default tasks: player worlds and plugin state of CityBuild and MineClash; built spawn worlds stay. */
        val DEFAULT_TASKS: List<ReleaseTask> = listOf(
            ReleaseTask(
                task = "CityBuild",
                wipe = listOf(
                    "plots",
                    "farm",
                    "farm_nether",
                    "farm_end",
                    "plugins/PlotSquared/*.db",
                    "plugins/CityBuild/shops.json",
                    "plugins/CityBuild/furniture.json",
                    "plugins/CityBuild/livestock.json",
                    "plugins/CityBuild/plot-activity.json",
                    "plugins/CityBuild/rail-state.json",
                    "plugins/CityBuild/unresolved.json",
                    "plugins/CityBuild/pending-pickups.json",
                ),
            ),
            ReleaseTask(task = "MineClash-Game", wipe = listOf("village_*")),
            ReleaseTask(task = "MineClash-War", wipe = emptyList()),
        )

        /**
         * Loads the configuration; writes the defaults when the file does not exist yet.
         *
         * @param configDir the node's config directory.
         * @return the configuration (defaults on read errors).
         */
        fun load(configDir: Path): ReleaseConfig {
            val file = configDir.resolve(FILE_NAME)
            if (!Files.exists(file)) {
                val defaults = ReleaseConfig()
                runCatching {
                    Files.createDirectories(configDir)
                    Files.writeString(file, json.encodeToString(serializer(), defaults))
                }
                return defaults
            }
            return runCatching { json.decodeFromString(serializer(), Files.readString(file)) }.getOrElse { ReleaseConfig() }
        }
    }
}

/**
 * One game task of the release reset.
 *
 * @property task task name.
 * @property wipe paths relative to each static service workspace of the task; the last path segment may contain
 *  `*` wildcards (for example `village_*` or a `*.db` file name below `plugins/PlotSquared`).
 */
@Serializable
data class ReleaseTask(
    val task: String,
    val wipe: List<String> = emptyList(),
)
