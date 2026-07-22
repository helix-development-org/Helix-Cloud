package org.helix.node.files

import kotlinx.serialization.Serializable

/**
 * Request body of `PUT /files/content`.
 *
 * @property root the file root, for example `static:Lobby-1` or
 *  `template:default`.
 * @property path file path relative to the root.
 * @property content new file text (UTF-8).
 */
@Serializable
data class FileWriteRequest(
    val root: String,
    val path: String,
    val content: String,
)
