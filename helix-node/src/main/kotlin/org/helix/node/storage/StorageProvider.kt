package org.helix.node.storage

import org.helix.api.storage.AddonStorage
import java.nio.file.Path

/**
 * Creates addon-scoped [AddonStorage] instances according to the node's
 * configured storage mode.
 */
interface StorageProvider {
    /**
     * Returns the storage for one addon.
     *
     * @param addonId owning addon id.
     * @param dataDirectory the addon's data directory (used by file mode).
     * @return the addon-scoped storage.
     */
    fun forAddon(addonId: String, dataDirectory: Path): AddonStorage

    /**
     * Releases held resources (for example the database pool).
     */
    fun close() {
    }
}
