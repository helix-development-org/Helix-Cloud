package org.helix.addons.bettermsgs.paper

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Borrows the player inventory while a chat GUI is open.
 *
 * The real contents are snapshotted in memory AND on disk before the chat
 * fills the inventory with message items, and restored on close, quit,
 * plugin disable — or on the next join after a crash, so player items are
 * never lost.
 *
 * @property directory folder for crash-safe snapshots (one file per uuid).
 */
class InventoryTakeover(private val directory: Path) {
    private val saved = ConcurrentHashMap<UUID, Array<ItemStack?>>()

    /**
     * Snapshots and clears the player inventory, once per takeover.
     *
     * @param player the player opening a chat.
     */
    fun begin(player: Player) {
        if (saved.containsKey(player.uniqueId)) {
            return
        }
        val contents = player.inventory.contents.map { it?.clone() }.toTypedArray()
        saved[player.uniqueId] = contents
        persist(player.uniqueId, contents)
        player.inventory.clear()
    }

    /**
     * Whether the player's inventory is currently taken over.
     *
     * @param player the player.
     * @return `true` while message items occupy the inventory.
     */
    fun active(player: Player): Boolean = saved.containsKey(player.uniqueId)

    /**
     * Restores the snapshotted contents and forgets the takeover.
     *
     * @param player the player.
     */
    fun restore(player: Player) {
        val contents = saved.remove(player.uniqueId) ?: return
        player.inventory.contents = contents
        Files.deleteIfExists(snapshotFile(player.uniqueId))
    }

    /**
     * Restores every active takeover, on plugin disable.
     *
     * @param resolver resolves online players by uuid.
     */
    fun restoreAll(resolver: (UUID) -> Player?) {
        saved.keys.toList().forEach { uuid -> resolver(uuid)?.let(::restore) }
    }

    /**
     * Restores a crash snapshot on join, when one exists.
     *
     * @param player the joining player.
     */
    fun restoreCrashed(player: Player) {
        if (saved.containsKey(player.uniqueId)) {
            return
        }
        val file = snapshotFile(player.uniqueId)
        if (!Files.exists(file)) {
            return
        }
        runCatching {
            val bytes = Files.readAllBytes(file)
            player.inventory.contents = deserialize(bytes)
        }
        Files.deleteIfExists(file)
    }

    private fun persist(uuid: UUID, contents: Array<ItemStack?>) {
        runCatching {
            Files.createDirectories(directory)
            Files.write(snapshotFile(uuid), serialize(contents))
        }
    }

    private fun snapshotFile(uuid: UUID): Path = directory.resolve("$uuid.inv")

    private fun serialize(contents: Array<ItemStack?>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(output).use { data ->
            data.writeInt(contents.size)
            contents.forEach { item ->
                val bytes = item?.takeIf { !it.isEmpty }?.serializeAsBytes()
                data.writeInt(bytes?.size ?: 0)
                bytes?.let(data::write)
            }
        }
        return output.toByteArray()
    }

    private fun deserialize(bytes: ByteArray): Array<ItemStack?> {
        java.io.DataInputStream(java.io.ByteArrayInputStream(bytes)).use { data ->
            val size = data.readInt()
            return Array(size) {
                val length = data.readInt()
                if (length == 0) null else ItemStack.deserializeBytes(data.readNBytes(length))
            }
        }
    }
}
