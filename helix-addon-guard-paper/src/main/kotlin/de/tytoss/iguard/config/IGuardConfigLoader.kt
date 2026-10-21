package de.tytoss.iguard.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin

/** [ConfigSource] over Bukkit's `config.yml` (jar defaults included). */
private class BukkitConfigSource(private val config: FileConfiguration) : ConfigSource {
    override fun string(path: String): String? = config.getString(path)

    override fun int(path: String, default: Int): Int = if (config.isSet(path)) config.getInt(path, default) else default

    override fun long(path: String, default: Long): Long = if (config.isSet(path)) config.getLong(path, default) else default

    override fun double(path: String, default: Double): Double = if (config.isSet(path)) config.getDouble(path, default) else default

    override fun boolean(path: String, default: Boolean): Boolean = if (config.isSet(path)) config.getBoolean(path, default) else default
}

/** Loads [IGuardConfig] from the plugin's `config.yml`. */
object IGuardConfigLoader {
    /**
     * Loads the static configuration from the plugin's `config.yml`. Persistence always runs
     * through the Helix node, so the HELIX_CONTROL_URL and HELIX_CONTROL_TOKEN environment
     * variables must be present (the Helix wrapper provides them); anything else fails fast
     * with a clear error instead of starting half-configured.
     */
    fun load(plugin: JavaPlugin): IGuardConfig {
        plugin.saveDefaultConfig()
        val source = plugin.config
        val serverId = value(source, "server-id")
        require(System.getenv("HELIX_CONTROL_URL").isNullOrBlank().not()) {
            "IGuard persists through a Helix-Cloud node: the HELIX_CONTROL_URL environment variable is required"
        }
        require(System.getenv("HELIX_CONTROL_TOKEN").isNullOrBlank().not()) {
            "IGuard persists through a Helix-Cloud node: the HELIX_CONTROL_TOKEN environment variable is required"
        }
        return IGuardConfig.parse(BukkitConfigSource(source), serverId)
    }

    /** Re-reads config.yml and returns the hot-reloadable (dynamic) part only. */
    fun reloadDynamic(plugin: JavaPlugin): DynamicConfig {
        plugin.reloadConfig()
        return IGuardConfig.dynamic(BukkitConfigSource(plugin.config))
    }

    private fun value(source: FileConfiguration, path: String): String {
        val raw = source.getString(path)?.trim().orEmpty()
        require(raw.isNotEmpty()) { "$path must not be empty" }
        return Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").replace(raw) { match ->
            System.getenv(match.groupValues[1])
                ?: throw IllegalArgumentException("Environment variable ${match.groupValues[1]} is not set")
        }
    }
}
