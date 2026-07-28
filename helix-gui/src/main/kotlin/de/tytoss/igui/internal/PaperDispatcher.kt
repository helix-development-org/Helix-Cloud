package de.tytoss.igui.internal

import kotlinx.coroutines.CoroutineDispatcher
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import kotlin.coroutines.CoroutineContext

internal class PaperDispatcher(private val plugin: JavaPlugin) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !Bukkit.isPrimaryThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        check(plugin.isEnabled) { "Plugin '${plugin.name}' is disabled" }
        plugin.server.scheduler.runTask(plugin, block)
    }
}
