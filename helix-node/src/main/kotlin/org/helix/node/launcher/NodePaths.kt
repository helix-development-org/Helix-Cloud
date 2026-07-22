package org.helix.node.launcher

import java.nio.file.Files
import java.nio.file.Path

/**
 * Well-known locations below the `Helix/` data directory.
 *
 * @property root the data directory root.
 */
class NodePaths(val root: Path) {
    /** Node configuration files. */
    val config: Path = root.resolve("config")

    /** Task definitions, one TOML file per task. */
    val tasks: Path = root.resolve("tasks")

    /** Template directories copied into service workspaces. */
    val templates: Path = root.resolve("templates")

    /** Persistent workspaces of static services. */
    val servicesStatic: Path = root.resolve("services/static")

    /** Throw-away workspaces of dynamic services. */
    val servicesTemp: Path = root.resolve("services/temp")

    /** Downloaded server jars. */
    val cache: Path = root.resolve("cache")

    /** Installed HXA addons. */
    val addons: Path = root.resolve("addons")

    /** Workspace backups, one directory per service. */
    val backups: Path = root.resolve("backups")

    /**
     * Creates all directories.
     *
     * @return this instance for chaining.
     */
    fun createAll(): NodePaths {
        listOf(config, tasks, templates, servicesStatic, servicesTemp, cache, addons, backups)
            .forEach(Files::createDirectories)
        return this
    }
}
