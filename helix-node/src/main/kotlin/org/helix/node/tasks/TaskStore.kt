package org.helix.node.tasks

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import org.helix.api.task.TaskDefinition

/**
 * Persistent registry of task definitions below `Helix/tasks/`.
 *
 * Every task is one `<name>.toml` file. The store keeps an in-memory view
 * that is refreshed from disk on [reload].
 *
 * @property directory the `Helix/tasks/` directory.
 */
class TaskStore(private val directory: Path) {
    private val tasks = linkedMapOf<String, TaskDefinition>()

    /**
     * Reads all task files from disk, replacing the in-memory view.
     *
     * @return the loaded definitions sorted by name.
     * @throws IllegalArgumentException if a file is invalid or its file name
     *   does not match the task name inside.
     */
    @Synchronized
    fun reload(): List<TaskDefinition> {
        Files.createDirectories(directory)
        tasks.clear()
        directory.listDirectoryEntries()
            .filter { it.extension == "toml" }
            .sortedBy { it.nameWithoutExtension }
            .forEach { file ->
                val task = TaskTomlCodec.parse(Files.readString(file))
                require(task.name == file.nameWithoutExtension) {
                    "task file ${file.fileName} declares mismatching name '${task.name}'"
                }
                tasks[task.name] = task
            }
        return all()
    }

    /**
     * Lists all known tasks.
     *
     * @return definitions sorted by name.
     */
    @Synchronized
    fun all(): List<TaskDefinition> = tasks.values.sortedBy { it.name }

    /**
     * Looks up a task by name.
     *
     * @param name the task name.
     * @return the definition or `null` if unknown.
     */
    @Synchronized
    fun find(name: String): TaskDefinition? = tasks[name]

    /**
     * Creates or updates a task and writes it to disk.
     *
     * @param task the definition to persist.
     */
    @Synchronized
    fun save(task: TaskDefinition) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("${task.name}.toml"), TaskTomlCodec.render(task))
        tasks[task.name] = task
    }

    /**
     * Deletes a task file and removes it from the in-memory view.
     *
     * @param name the task name.
     * @return `true` if the task existed.
     */
    @Synchronized
    fun delete(name: String): Boolean {
        val existed = tasks.remove(name) != null
        Files.deleteIfExists(directory.resolve("$name.toml"))
        return existed
    }
}
