package org.helix.node.tasks

import org.helix.api.environment.Environment
import org.helix.api.execution.ExecutorType
import org.helix.api.task.AutoScaleSettings
import org.helix.api.task.TaskDefinition
import org.tomlj.Toml
import org.tomlj.TomlParseResult

/**
 * Converts between [TaskDefinition] and the on-disk TOML task format.
 */
object TaskTomlCodec {
    /**
     * Parses a task file.
     *
     * @param content TOML text of a `Helix/tasks/<name>.toml` file.
     * @return the parsed definition.
     * @throws IllegalArgumentException on syntax errors or missing keys.
     */
    fun parse(content: String): TaskDefinition {
        val toml: TomlParseResult = Toml.parse(content)
        require(!toml.hasErrors()) {
            "invalid task toml: ${toml.errors().joinToString { it.toString() }}"
        }
        val defaults = TaskDefinition(
            name = "placeholder",
            environment = Environment.PAPER,
            version = "0",
        )
        return TaskDefinition(
            name = requireNotNull(toml.getString("name")) { "task key missing: name" },
            environment = Environment.valueOf(
                requireNotNull(toml.getString("environment")) { "task key missing: environment" }.uppercase(),
            ),
            version = requireNotNull(toml.getString("version")) { "task key missing: version" },
            executor = toml.getString("executor")?.uppercase()?.let(ExecutorType::valueOf)
                ?: defaults.executor,
            staticServices = toml.getBoolean("staticServices") ?: defaults.staticServices,
            minServiceCount = toml.getLong("minServiceCount")?.toInt() ?: defaults.minServiceCount,
            maxServiceCount = toml.getLong("maxServiceCount")?.toInt() ?: defaults.maxServiceCount,
            memoryMb = toml.getLong("memoryMb")?.toInt() ?: defaults.memoryMb,
            maxPlayers = toml.getLong("maxPlayers")?.toInt() ?: defaults.maxPlayers,
            startPort = toml.getLong("startPort")?.toInt() ?: defaults.startPort,
            jvmArgs = toml.getArrayOrEmpty("jvmArgs").toList().map { it.toString() },
            templates = toml.getArray("templates")?.toList()?.map { it.toString() }
                ?: defaults.templates,
            fallbackEligible = toml.getBoolean("fallbackEligible") ?: defaults.fallbackEligible,
            maintenance = toml.getBoolean("maintenance") ?: defaults.maintenance,
            disabledAddons = toml.getArray("disabledAddons")?.toList()?.map { it.toString() }
                ?: defaults.disabledAddons,
            autoScale = AutoScaleSettings(
                enabled = toml.getBoolean("autoScale.enabled") ?: false,
                playerRatioThreshold = toml.getDouble("autoScale.playerRatioThreshold") ?: 0.8,
                idleStopSeconds = toml.getLong("autoScale.idleStopSeconds") ?: 300,
            ),
        )
    }

    /**
     * Renders a task definition as TOML.
     *
     * @param task the definition to render.
     * @return TOML text parseable by [parse].
     */
    fun render(task: TaskDefinition): String = buildString {
        appendLine("name = ${task.name.tomlQuoted()}")
        appendLine("environment = \"${task.environment.name}\"")
        appendLine("version = ${task.version.tomlQuoted()}")
        appendLine("executor = \"${task.executor.name}\"")
        appendLine("staticServices = ${task.staticServices}")
        appendLine("minServiceCount = ${task.minServiceCount}")
        appendLine("maxServiceCount = ${task.maxServiceCount}")
        appendLine("memoryMb = ${task.memoryMb}")
        appendLine("maxPlayers = ${task.maxPlayers}")
        appendLine("startPort = ${task.startPort}")
        appendLine("jvmArgs = [${task.jvmArgs.joinToString { it.tomlQuoted() }}]")
        appendLine("templates = [${task.templates.joinToString { it.tomlQuoted() }}]")
        appendLine("fallbackEligible = ${task.fallbackEligible}")
        appendLine("maintenance = ${task.maintenance}")
        appendLine("disabledAddons = [${task.disabledAddons.joinToString { it.tomlQuoted() }}]")
        appendLine()
        appendLine("[autoScale]")
        appendLine("enabled = ${task.autoScale.enabled}")
        appendLine("playerRatioThreshold = ${task.autoScale.playerRatioThreshold}")
        appendLine("idleStopSeconds = ${task.autoScale.idleStopSeconds}")
    }

    /**
     * Quotes a string as a TOML basic string.
     *
     * @return the quoted representation.
     */
    private fun String.tomlQuoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
