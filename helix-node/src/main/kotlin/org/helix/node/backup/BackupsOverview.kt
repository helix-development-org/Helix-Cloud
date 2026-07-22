package org.helix.node.backup

import kotlinx.serialization.Serializable

/**
 * Everything the backups panel needs: the static workspaces that can be
 * backed up (with their running state, which blocks restores) and all
 * existing backup archives.
 *
 * @property workspaces static service workspaces on disk.
 * @property backups all backup archives, newest first.
 */
@Serializable
data class BackupsOverview(
    val workspaces: List<WorkspaceState> = emptyList(),
    val backups: List<BackupInfo> = emptyList(),
) {
    /**
     * A static service workspace and whether its service is running.
     *
     * @property serviceId the workspace/service id.
     * @property running whether the service is currently active.
     */
    @Serializable
    data class WorkspaceState(
        val serviceId: String,
        val running: Boolean,
    )
}
