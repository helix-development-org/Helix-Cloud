package de.tytoss.iguard.ban

import de.tytoss.iguard.api.BanProvider
import de.tytoss.iguard.config.BansConfig
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.logging.Logger

/**
 * Delegates enforcement to any external ban plugin by dispatching configurable console commands. Works
 * with every ban system that exposes commands (LiteBans, AdvancedBan, EssentialsX, BanManager, …) with
 * no compile-time dependency. Placeholders: %player% %uuid% %reason% %hours% %actor%.
 */
class CommandBanProvider(
    private val plugin: JavaPlugin,
    private val config: BansConfig,
    private val logger: Logger
) : BanProvider {
    override val id = "command"
    override val ownsEnforcementGate = false

    override fun ban(playerId: UUID, playerName: String, hours: Int, reason: String, actor: String) {
        // Use the tempban template for finite durations if configured, else the plain ban template.
        val template = if (hours < 8760 && config.tempbanCommand.isNotBlank()) config.tempbanCommand else config.banCommand
        dispatch(template, playerId, playerName, hours, reason, actor)
    }

    override fun unban(playerId: UUID, playerName: String, actor: String) {
        dispatch(config.unbanCommand, playerId, playerName, 0, "", actor)
    }

    private fun dispatch(template: String, playerId: UUID, playerName: String, hours: Int, reason: String, actor: String) {
        if (template.isBlank()) { logger.warning("bans.provider=command but no command template configured"); return }
        val command = template
            .replace("%player%", playerName)
            .replace("%uuid%", playerId.toString())
            .replace("%hours%", hours.toString())
            .replace("%reason%", reason)
            .replace("%actor%", actor)
            .removePrefix("/")
        // Console dispatch must run on the main thread.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            runCatching { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command) }
                .onFailure { logger.warning("Ban command failed: ${it.message}") }
        })
    }
}
