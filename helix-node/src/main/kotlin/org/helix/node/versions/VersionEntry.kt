package org.helix.node.versions

import org.helix.api.environment.Environment

/**
 * A single entry of `config/versions.toml`.
 *
 * @property environment platform the entry belongs to.
 * @property version platform version string.
 * @property url optional direct download override; when set, the PaperMC
 *   API is skipped entirely.
 */
data class VersionEntry(
    val environment: Environment,
    val version: String,
    val url: String? = null,
)
