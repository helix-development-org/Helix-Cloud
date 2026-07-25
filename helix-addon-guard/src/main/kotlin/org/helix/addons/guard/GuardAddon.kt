package org.helix.addons.guard

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.action.ActionSource

/**
 * Node-side management addon for the IGuard anticheat Paper plugin
 * ("Helix-Guard").
 *
 * Every IGuard config value is editable through the `guard.config.*`
 * actions and the "Guard" dashboard panel. On every change the addon
 * renders a complete `plugins/IGuard/config.yml` from defaults plus
 * overrides, writes it into every service workspace and template and
 * sends `iguard reload` to all running services. Static values (database,
 * workers, history writer, dashboard) additionally need a service restart;
 * `server-id` is always the literal `${HELIX_SERVICE_ID}` so IGuard
 * resolves it per service from the environment.
 */
class GuardAddon : AddonBase() {
    private lateinit var store: GuardConfigStore
    private val json = Json

    /**
     * Registers the `guard.*` actions and the dashboard panel.
     */
    override fun enable() {
        store = GuardConfigStore(context.storage())
        action(
            "guard.config.get",
            "Lists every IGuard config value with its effective value, default, type and static flag.",
            "guard.config.get",
        ) { configGet() }
        action(
            "guard.config.set",
            "Overrides an IGuard config value, distributes the config and hot-reloads running services.",
            "guard.config.set <path> <value...>",
        ) { invocation -> configSet(invocation) }
        action(
            "guard.config.reset",
            "Removes one override (or all) and redistributes the config.",
            "guard.config.reset <path|all>",
        ) { invocation -> configReset(invocation) }
        action(
            "guard.apply",
            "Writes the IGuard config into all service directories and hot-reloads running services.",
            "guard.apply",
        ) { applyConfig(emptyList()) }
        panel(
            "guard",
            "Guard",
            "/panel.html",
            "<path d=\"M12 3l7 3v5c0 4.4-3 8.4-7 10-4-1.6-7-5.6-7-10V6z\"/><path d=\"M9 12l2 2 4-4\"/>",
        )
    }

    private fun configGet(): ActionResult {
        val overrides = store.overrides()
        val views = GuardConfig.settings.map { setting ->
            val override = overrides[setting.path]
            GuardSettingView(
                path = setting.path,
                value = override ?: setting.default,
                default = setting.default,
                type = setting.type.id,
                static = setting.static,
                overridden = override != null,
            )
        }
        return ActionResult.ok(json.encodeToString(views))
    }

    private fun configSet(invocation: ActionInvocation): ActionResult {
        val path = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("usage: guard.config.set <path> <value...>")
        if (invocation.arguments.size < 2) {
            return ActionResult.error("usage: guard.config.set <path> <value...>")
        }
        if (path == "server-id") {
            return ActionResult.error(
                "server-id is fixed to ${GuardConfig.SERVER_ID_VALUE} and resolved per service by IGuard",
            )
        }
        val setting = GuardConfig.byPath[path]
            ?: return ActionResult.error("unknown config path: $path")
        val raw = invocation.arguments.drop(1).joinToString(" ")
        val value = setting.type.canonicalize(raw)
            ?: return ActionResult.error("invalid ${setting.type.id} value for $path: $raw")
        store.set(path, value)
        return applyConfig(if (setting.static) listOf(path) else emptyList())
    }

    private fun configReset(invocation: ActionInvocation): ActionResult {
        val target = invocation.arguments.getOrNull(0)
            ?: return ActionResult.error("usage: guard.config.reset <path|all>")
        val removed: List<String>
        if (target == "all") {
            removed = store.clear()
        } else {
            GuardConfig.byPath[target]
                ?: return ActionResult.error("unknown config path: $target")
            removed = if (store.remove(target)) listOf(target) else emptyList()
        }
        val changedStatic = removed.filter { GuardConfig.byPath[it]?.static == true }
        val summary = applyConfig(changedStatic)
        val header = if (removed.isEmpty()) "no override to remove" else "removed ${removed.size} override(s)"
        return ActionResult(summary.success, listOf(header) + summary.lines)
    }

    /**
     * Renders the config and distributes it to every first-level
     * subdirectory (service workspace or template) of every service root,
     * then sends `iguard reload` to all running services.
     */
    private fun applyConfig(changedStaticPaths: List<String>): ActionResult {
        val yaml = GuardConfig.renderConfigYaml(store.overrides())
        var written = 0
        context.serviceDirectories().forEach { root ->
            serviceSubdirectories(root).forEach { subdirectory ->
                val target = subdirectory.resolve("plugins").resolve("IGuard").resolve("config.yml")
                Files.createDirectories(target.parent)
                Files.writeString(target, yaml)
                written++
            }
        }
        val listResult = context.actions.invoke(
            ActionInvocation("service.list", source = ActionSource.ADDON),
        )
        val running = listResult.lines.mapNotNull { line -> RUNNING_LINE.find(line)?.groupValues?.get(1) }
        running.forEach { id ->
            context.actions.invoke(
                ActionInvocation("service.command", listOf(id, "iguard", "reload"), ActionSource.ADDON),
            )
        }
        val lines = mutableListOf(
            "config.yml written to $written service director${if (written == 1) "y" else "ies"}",
            if (running.isEmpty()) {
                "no running services to reload"
            } else {
                "iguard reload sent to ${running.size} running service(s): ${running.joinToString(", ")}"
            },
        )
        if (changedStaticPaths.isNotEmpty()) {
            lines += "warning: static value(s) changed, requires service restart: " +
                changedStaticPaths.sorted().joinToString(", ")
        }
        return ActionResult.ok(*lines.toTypedArray())
    }

    private fun serviceSubdirectories(root: Path): List<Path> {
        if (!Files.isDirectory(root)) {
            return emptyList()
        }
        Files.newDirectoryStream(root).use { children ->
            return children.filter { Files.isDirectory(it) }.sortedBy { it.fileName.toString() }
        }
    }

    private companion object {
        /** Matches a `service.list` line of a running service and captures its id. */
        val RUNNING_LINE = Regex("""^(\S+) \[RUNNING]""")
    }
}
