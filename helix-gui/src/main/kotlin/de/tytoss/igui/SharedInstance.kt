package de.tytoss.igui

import kotlinx.coroutines.delay
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * Registers this instance on Bukkit's [org.bukkit.plugin.ServicesManager] so
 * other plugins can retrieve it via [awaitSharedIGui] instead of each
 * installing (and configuring fonts/a database for) their own instance.
 *
 * @param plugin the plugin that installed this instance (the service owner).
 */
fun IGui.registerShared(plugin: JavaPlugin) {
    Bukkit.getServicesManager().register(IGui::class.java, this, plugin, ServicePriority.Normal)
}

/**
 * Waits for another plugin's [registerShared] instance to become available,
 * polling the [org.bukkit.plugin.ServicesManager] since the owning plugin's
 * [IGui.install] runs asynchronously and may not have completed yet.
 *
 * @param intervalMs delay between polls.
 * @param maxAttempts polls before giving up.
 * @return the shared instance.
 * @throws IllegalStateException if it never becomes available in time.
 */
suspend fun awaitSharedIGui(intervalMs: Long = 250, maxAttempts: Int = 80): IGui {
    repeat(maxAttempts) {
        Bukkit.getServicesManager().load(IGui::class.java)?.let { return it }
        delay(intervalMs)
    }
    error("No shared IGui instance was registered in time — is the owning plugin installed and enabled?")
}
