package org.helix.node.files

import kotlinx.serialization.Serializable

/**
 * Text content of a file read through the file manager.
 *
 * @property content the file text (UTF-8).
 */
@Serializable
data class FileContent(val content: String)
