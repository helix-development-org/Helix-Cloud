package org.helix.node.services

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import org.helix.api.environment.Environment
import org.helix.api.task.TaskDefinition
import org.helix.node.launcher.NodePaths
import org.helix.node.resources.InternalResources
import org.slf4j.LoggerFactory

/**
 * Builds service workspaces.
 *
 * Static services keep their workspace below `services/static/<id>` across
 * restarts; dynamic services always start from a fresh workspace below
 * `services/temp/<id>` that the manager deletes after the service stopped.
 *
 * @property paths data directory layout.
 * @property internalResources embedded wrapper and bridge jars.
 * @property serverJar resolves the cached server jar for an environment and
 *   version, downloading it on first use.
 * @property paperComponents Paper-side plugin components of enabled addons
 *   active for a task (addon id to jar path), installed into Paper
 *   workspaces and refreshed on every service start.
 */
class WorkspacePreparer(
    private val paths: NodePaths,
    private val internalResources: InternalResources,
    private val serverJar: (Environment, String) -> Path,
    private val paperComponents: (taskName: String) -> List<Pair<String, Path>> = { emptyList() },
    private val velocityComponents: (taskName: String) -> List<Pair<String, Path>> = { emptyList() },
) {
    private val logger = LoggerFactory.getLogger(WorkspacePreparer::class.java)

    /**
     * Prepares the workspace of one service.
     *
     * Node-owned files (`Wrapper.jar`, bridge plugin, `server.jar`,
     * `wrapper.properties`) are always refreshed; platform configuration is
     * only generated when missing so static services keep manual edits.
     *
     * @param task task the service belongs to.
     * @param serviceId id of the service.
     * @param port port the service listens on.
     * @return the workspace root.
     */
    fun prepare(task: TaskDefinition, serviceId: String, port: Int): Path {
        val workspace = workspaceFor(task, serviceId)
        if (!task.staticServices) {
            deleteRecursively(workspace)
        }
        Files.createDirectories(workspace)
        copyTemplates(task, workspace)
        installServerJar(task, workspace)
        installWrapper(workspace)
        installBridge(task.environment, workspace)
        installAddonComponents(task, workspace)
        writeWrapperProperties(task, serviceId, workspace)
        writePlatformDefaults(task, port, workspace)
        logger.info("Prepared workspace for {} at {}", serviceId, workspace)
        return workspace
    }

    /**
     * Resolves the workspace path of a service without touching disk.
     *
     * @param task task the service belongs to.
     * @param serviceId id of the service.
     * @return static or temp workspace path depending on the task.
     */
    fun workspaceFor(task: TaskDefinition, serviceId: String): Path =
        (if (task.staticServices) paths.servicesStatic else paths.servicesTemp).resolve(serviceId)

    /**
     * Deletes a workspace tree, ignoring missing paths.
     *
     * @param workspace the workspace root to delete.
     */
    fun deleteRecursively(workspace: Path) {
        if (Files.notExists(workspace)) {
            return
        }
        Files.walkFileTree(
            workspace,
            /** Depth-first deletion visitor. */
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun copyTemplates(task: TaskDefinition, workspace: Path) {
        task.templates.forEach { template ->
            val source = paths.templates.resolve(template)
            if (Files.notExists(source)) {
                return@forEach
            }
            Files.walk(source).use { stream ->
                stream.forEach { path ->
                    val target = workspace.resolve(source.relativize(path).toString())
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    private fun installServerJar(task: TaskDefinition, workspace: Path) {
        val jar = serverJar(task.environment, task.version)
        Files.copy(jar, workspace.resolve("server.jar"), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun installWrapper(workspace: Path) {
        internalResources.open("helix-internal/Wrapper.jar").use { stream ->
            Files.copy(stream, workspace.resolve("Wrapper.jar"), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun installBridge(environment: Environment, workspace: Path) {
        val bridgeName = when (environment) {
            Environment.PAPER -> "HelixPaperBridge.jar"
            Environment.VELOCITY -> "HelixVelocityBridge.jar"
        }
        val plugins = workspace.resolve("plugins")
        Files.createDirectories(plugins)
        internalResources.open("helix-internal/bridges/$bridgeName").use { stream ->
            Files.copy(stream, plugins.resolve(bridgeName), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Installs the Paper components of active addons as plugins, named
     * `HelixAddon-<id>.jar`. Stale components (addon disabled or newly
     * inactive for the task) are removed, so a service restart always
     * reflects the current addon state.
     */
    private fun installAddonComponents(task: TaskDefinition, workspace: Path) {
        val components = when (task.environment) {
            Environment.PAPER -> paperComponents(task.name)
            Environment.VELOCITY -> velocityComponents(task.name)
        }
        val plugins = workspace.resolve("plugins")
        Files.createDirectories(plugins)
        val desired = components.associateBy { (id, _) -> componentFileName(id) }
        Files.list(plugins).use { stream ->
            stream.filter { it.fileName.toString().let { name -> name.startsWith(COMPONENT_PREFIX) && name.endsWith(".jar") } }
                .filter { it.fileName.toString() !in desired }
                .forEach(Files::delete)
        }
        desired.forEach { (fileName, component) ->
            val (id, jar) = component
            Files.copy(jar, plugins.resolve(fileName), StandardCopyOption.REPLACE_EXISTING)
            logger.debug("Installed paper component of {} into {}", id, workspace)
        }
    }

    private fun componentFileName(addonId: String): String =
        COMPONENT_PREFIX + addonId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jar"

    private fun writeWrapperProperties(task: TaskDefinition, serviceId: String, workspace: Path) {
        val serverArgs = when (task.environment) {
            Environment.PAPER -> "--nogui"
            Environment.VELOCITY -> ""
        }
        Files.writeString(
            workspace.resolve("wrapper.properties"),
            buildString {
                appendLine("serviceId=$serviceId")
                appendLine("serverJar=server.jar")
                appendLine("memoryMb=${task.memoryMb}")
                appendLine("jvmArgs=${task.jvmArgs.joinToString(" ")}")
                appendLine("serverArgs=$serverArgs")
            },
        )
    }

    private fun writePlatformDefaults(task: TaskDefinition, port: Int, workspace: Path) {
        when (task.environment) {
            Environment.PAPER -> {
                writeIfMissing(workspace.resolve("eula.txt"), "eula=true\n")
                writeIfMissing(
                    workspace.resolve("server.properties"),
                    buildString {
                        appendLine("server-port=$port")
                        appendLine("max-players=${task.maxPlayers}")
                        appendLine("online-mode=false")
                        appendLine("motd=${task.name}")
                    },
                )
                // Legacy proxy forwarding requires bungeecord mode, otherwise
                // paper rejects players coming through velocity.
                writeIfMissing(
                    workspace.resolve("spigot.yml"),
                    buildString {
                        appendLine("settings:")
                        appendLine("  bungeecord: true")
                    },
                )
            }
            Environment.VELOCITY -> {
                // The config must be complete: without config-version and an
                // explicit (empty) forced-hosts section velocity fills the
                // gaps with its example defaults, which reference servers
                // that do not exist and abort the startup.
                writeIfMissing(
                    workspace.resolve("velocity.toml"),
                    buildString {
                        appendLine("config-version = \"2.7\"")
                        appendLine("bind = \"0.0.0.0:$port\"")
                        appendLine("motd = \"${task.name}\"")
                        appendLine("show-max-players = ${task.maxPlayers}")
                        appendLine("online-mode = true")
                        appendLine("player-info-forwarding-mode = \"legacy\"")
                        appendLine()
                        appendLine("[servers]")
                        appendLine("try = []")
                        appendLine()
                        appendLine("[forced-hosts]")
                    },
                )
            }
        }
    }

    private fun writeIfMissing(file: Path, content: String) {
        if (Files.notExists(file)) {
            Files.writeString(file, content)
        }
    }

    private companion object {
        /** File-name prefix of addon-provided Paper plugin components. */
        const val COMPONENT_PREFIX = "HelixAddon-"
    }
}
