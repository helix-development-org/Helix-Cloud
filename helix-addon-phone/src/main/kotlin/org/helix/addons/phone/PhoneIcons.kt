package org.helix.addons.phone

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.helix.api.addon.AddonContext

/**
 * Shared icon constants agreed between the node (runtime carrier-model
 * regeneration for uploaded icons) and the paper component (which item and
 * `CustomModelData` to render) plus the build-time pack generator (built-in
 * icon models). Keep in sync with the paper mirror.
 *
 * App icons are real items — a [CARRIER] carrier whose model is swapped by
 * `CustomModelData` (the same technique as the cosmetics addon), so a tile is
 * a normal, click-aligned inventory item rather than a title glyph.
 */
object PhoneIcons {
    /** Namespace of every phone pack asset. */
    const val NAMESPACE = "helix_phone"

    /**
     * The carrier item whose model each icon overrides. Deliberately not
     * `paper` (the cosmetics addon's carrier) so the two never fight over the
     * same carrier model files.
     */
    const val CARRIER = "HEART_OF_THE_SEA"

    /** The carrier's vanilla model/texture id (lowercase material). */
    const val CARRIER_MODEL = "heart_of_the_sea"

    /** First `CustomModelData` value used for uploaded icons. */
    const val UPLOAD_CMD_BASE = 1000

    /** Built-in icon name to its `CustomModelData`, matching the baked pack. */
    val BUILTIN_CMD = mapOf(
        "default" to 1,
        "messages" to 2,
        "navigator" to 3,
        "profile" to 4,
        "settings" to 5,
        "guard" to 6,
        "network" to 7,
    )
}

/**
 * Runtime registry of uploaded app icons. Maps an icon id to a
 * `CustomModelData` value, persists the mapping, writes each uploaded PNG
 * plus its item model as runtime pack contributions, regenerates the carrier
 * model files (legacy overrides + modern range_dispatch, listing built-ins
 * and uploads) and triggers a pack rebuild.
 *
 * @property storage the addon storage backing the registry.
 * @property context the addon context for pack contributions/rebuilds.
 */
class PhoneIconStore(
    private val storage: org.helix.api.storage.AddonStorage,
    private val context: AddonContext,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), Int.serializer())
    private val registry: MutableMap<String, Int> = load()

    /** The known uploaded icon ids. */
    fun ids(): Set<String> = registry.keys.toSet()

    /**
     * Resolves an app's icon reference to its carrier `CustomModelData`.
     * Unknown or missing icons fall back to the built-in default.
     *
     * @param icon the `builtin:<name>` / `custom:<id>` reference.
     * @return the `CustomModelData` value to put on the carrier item.
     */
    fun resolve(icon: String): Int {
        if (icon.startsWith("custom:")) {
            registry[icon.removePrefix("custom:")]?.let { return it }
        }
        val builtin = icon.removePrefix("builtin:")
        return PhoneIcons.BUILTIN_CMD[builtin] ?: PhoneIcons.BUILTIN_CMD.getValue("default")
    }

    /**
     * Stores (or replaces) an uploaded icon PNG and its model, regenerates
     * the carrier models and rebuilds the network pack.
     *
     * @param iconId the stable icon id.
     * @param bytes the PNG bytes.
     * @return `true` when accepted, `false` when the bytes are not a PNG.
     */
    fun put(iconId: String, bytes: ByteArray): Boolean {
        if (!isPng(bytes)) {
            return false
        }
        if (iconId !in registry) {
            registry[iconId] = PhoneIcons.UPLOAD_CMD_BASE + nextFreeIndex()
            save()
        }
        context.contributePackAsset(texturePath(iconId), bytes)
        context.contributePackAsset(modelPath(iconId), itemModel("upload_$iconId").encodeToByteArray())
        regenerateCarrier()
        context.rebuildNetworkPack()
        return true
    }

    /**
     * Removes an uploaded icon, its assets and rebuilds the pack.
     *
     * @param iconId the icon id to remove.
     */
    fun remove(iconId: String) {
        if (registry.remove(iconId) == null) {
            return
        }
        save()
        context.removePackAsset(texturePath(iconId))
        context.removePackAsset(modelPath(iconId))
        regenerateCarrier()
        context.rebuildNetworkPack()
    }

    /**
     * Regenerates the carrier's legacy-overrides and modern range_dispatch
     * model files, listing every built-in and uploaded icon. When no icon is
     * uploaded, the files are removed so the pack's baked (built-in only)
     * versions apply.
     */
    private fun regenerateCarrier() {
        val overridesPath = "assets/minecraft/models/item/${PhoneIcons.CARRIER_MODEL}.json"
        val itemsPath = "assets/minecraft/items/${PhoneIcons.CARRIER_MODEL}.json"
        if (registry.isEmpty()) {
            context.removePackAsset(overridesPath)
            context.removePackAsset(itemsPath)
            return
        }
        val all = allIcons()
        context.contributePackAsset(overridesPath, carrierOverrides(all).encodeToByteArray())
        context.contributePackAsset(itemsPath, carrierItemModel(all).encodeToByteArray())
    }

    /** Every icon's `CustomModelData` paired with its model name, sorted. */
    private fun allIcons(): List<Pair<Int, String>> = buildList {
        PhoneIcons.BUILTIN_CMD.forEach { (name, cmd) -> add(cmd to "icon_$name") }
        registry.forEach { (iconId, cmd) -> add(cmd to "upload_$iconId") }
    }.sortedBy { it.first }

    private fun carrierOverrides(all: List<Pair<Int, String>>): String {
        val overrides = all.joinToString(",") { (cmd, model) ->
            """{"predicate":{"custom_model_data":$cmd},"model":"${PhoneIcons.NAMESPACE}:item/$model"}"""
        }
        return """{"parent":"minecraft:item/generated",""" +
            """"textures":{"layer0":"minecraft:item/${PhoneIcons.CARRIER_MODEL}"},"overrides":[$overrides]}"""
    }

    private fun carrierItemModel(all: List<Pair<Int, String>>): String {
        val entries = all.joinToString(",") { (cmd, model) ->
            """{"threshold":$cmd,"model":{"type":"minecraft:model","model":"${PhoneIcons.NAMESPACE}:item/$model"}}"""
        }
        return """{"model":{"type":"minecraft:range_dispatch","property":"minecraft:custom_model_data","index":0,""" +
            """"fallback":{"type":"minecraft:model","model":"minecraft:item/${PhoneIcons.CARRIER_MODEL}"},"entries":[$entries]}}"""
    }

    private fun itemModel(model: String): String =
        """{"parent":"minecraft:item/generated","textures":{"layer0":"${PhoneIcons.NAMESPACE}:item/$model"}}"""

    private fun nextFreeIndex(): Int {
        val used = registry.values.map { it - PhoneIcons.UPLOAD_CMD_BASE }.toSet()
        var index = 0
        while (index in used) {
            index += 1
        }
        return index
    }

    private fun texturePath(iconId: String): String =
        "assets/${PhoneIcons.NAMESPACE}/textures/item/upload_$iconId.png"

    private fun modelPath(iconId: String): String =
        "assets/${PhoneIcons.NAMESPACE}/models/item/upload_$iconId.json"

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()

    private fun load(): MutableMap<String, Int> =
        storage.read(STORAGE_KEY)?.let {
            runCatching { json.decodeFromString(mapSerializer, it).toMutableMap() }.getOrNull()
        } ?: mutableMapOf()

    private fun save() {
        storage.write(STORAGE_KEY, json.encodeToString(mapSerializer, registry))
    }

    private companion object {
        /** Storage key of the icon-id to CustomModelData registry. */
        const val STORAGE_KEY = "phone.icons"
    }
}
