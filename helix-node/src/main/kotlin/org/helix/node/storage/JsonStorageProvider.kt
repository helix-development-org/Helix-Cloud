package org.helix.node.storage

import java.nio.file.Path
import org.helix.api.storage.AddonStorage

/**
 * [StorageProvider] for the `json` mode: each addon stores documents as
 * files in its own data directory.
 */
class JsonStorageProvider : StorageProvider {
    override fun forAddon(addonId: String, dataDirectory: Path): AddonStorage =
        JsonFileAddonStorage(dataDirectory)
}
