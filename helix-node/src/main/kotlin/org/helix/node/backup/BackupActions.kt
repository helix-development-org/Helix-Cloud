package org.helix.node.backup

import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionResult
import org.helix.node.actions.ActionRegistry

/**
 * Registers the backup actions, making backups usable from the CLI, the
 * dashboard action console and — via the job scheduler — as recurring jobs
 * (for example `backup.create Lobby-1` daily at 04:00).
 *
 * @property backups the backup service.
 */
class BackupActions(private val backups: BackupService) {
    /**
     * Registers `backup.create`, `backup.list`, `backup.restore`,
     * `backup.delete`, `backup.create-data` and `backup.restore-data`.
     *
     * @param registry target registry.
     */
    fun registerAll(registry: ActionRegistry) {
        registry.register(
            ActionDescriptor(
                "backup.create",
                "Creates a zip backup of a static service workspace.",
                "backup.create <serviceId>",
            ),
        ) { invocation ->
            val serviceId = invocation.arguments.firstOrNull()
                ?: return@register ActionResult.error("usage: backup.create <serviceId>")
            val info = backups.create(serviceId)
            ActionResult.ok("created ${info.fileName} (${info.sizeBytes / 1024} KiB) for ${info.serviceId}")
        }
        registry.register(
            ActionDescriptor("backup.list", "Lists backup archives.", "backup.list [serviceId]"),
        ) { invocation ->
            val archives = backups.list(invocation.arguments.firstOrNull())
            if (archives.isEmpty()) {
                ActionResult.ok("no backups")
            } else {
                ActionResult.ok(
                    *archives.map { "${it.serviceId}/${it.fileName} (${it.sizeBytes / 1024} KiB)" }.toTypedArray(),
                )
            }
        }
        registry.register(
            ActionDescriptor(
                "backup.restore",
                "Restores a backup into the (stopped) service workspace.",
                "backup.restore <serviceId> <file>",
            ),
        ) { invocation ->
            val serviceId = invocation.arguments.getOrNull(0)
            val file = invocation.arguments.getOrNull(1)
            if (serviceId == null || file == null) {
                return@register ActionResult.error("usage: backup.restore <serviceId> <file>")
            }
            backups.restore(serviceId, file)
            ActionResult.ok("restored $file into $serviceId")
        }
        registry.register(
            ActionDescriptor("backup.delete", "Deletes a backup archive.", "backup.delete <serviceId> <file>"),
        ) { invocation ->
            val serviceId = invocation.arguments.getOrNull(0)
            val file = invocation.arguments.getOrNull(1)
            if (serviceId == null || file == null) {
                return@register ActionResult.error("usage: backup.delete <serviceId> <file>")
            }
            if (backups.delete(serviceId, file)) {
                ActionResult.ok("deleted $serviceId/$file")
            } else {
                ActionResult.error("unknown backup: $serviceId/$file")
            }
        }
        registry.register(
            ActionDescriptor(
                "backup.create-data",
                "Creates a zip backup of the json-mode addon/task/translation/audit data.",
                "backup.create-data",
            ),
        ) {
            val info = backups.createData()
            ActionResult.ok("created ${info.fileName} (${info.sizeBytes / 1024} KiB) addon-data backup")
        }
        registry.register(
            ActionDescriptor(
                "backup.restore-data",
                "Restores a json-mode addon-data backup.",
                "backup.restore-data <file>",
            ),
        ) { invocation ->
            val file = invocation.arguments.firstOrNull()
                ?: return@register ActionResult.error("usage: backup.restore-data <file>")
            backups.restoreData(file)
            ActionResult.ok("restored addon-data backup $file")
        }
    }
}
