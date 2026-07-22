package org.helix.node.backup

import kotlinx.serialization.Serializable

/**
 * One workspace backup archive.
 *
 * @property serviceId the static service the backup belongs to.
 * @property fileName archive file name, for example `20260722-040000.zip`.
 * @property sizeBytes archive size in bytes.
 * @property createdAtEpochMs when the backup was taken.
 */
@Serializable
data class BackupInfo(
    val serviceId: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAtEpochMs: Long,
)
