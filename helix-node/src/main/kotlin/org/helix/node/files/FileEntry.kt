package org.helix.node.files

import kotlinx.serialization.Serializable

/**
 * One entry in a file-manager directory listing.
 *
 * @property name file or directory name.
 * @property directory whether the entry is a directory.
 * @property sizeBytes file size; `0` for directories.
 * @property modifiedAtEpochMs last modification time.
 */
@Serializable
data class FileEntry(
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long,
)
