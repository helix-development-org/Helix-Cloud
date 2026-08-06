package org.helix.node.storage

import org.helix.api.storage.AddonStorage
import java.nio.file.Path

/**
 * [StorageProvider] for the `json` mode: each addon stores documents as
 * files in its own data directory.
 */
class JsonStorageProvider : StorageProvider {
    override fun forAddon(addonId: String, dataDirectory: Path): AddonStorage =
        JsonFileAddonStorage(dataDirectory)
}
