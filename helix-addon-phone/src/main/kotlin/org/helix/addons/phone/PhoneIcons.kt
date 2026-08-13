package org.helix.addons.phone

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.helix.api.addon.AddonContext

/**
 * Shared icon constants agreed between the node (uploads font generation)
 * and the paper component (rendering + built-in font baking). Keep these in
 * sync with the paper mirror.
 */
object PhoneIcons {
    /** Namespace of every phone pack asset. */
    const val NAMESPACE = "helix_phone"

    /** Base font key of the built-in icons (baked at build time). */
    const val BUILTIN_FONT = "$NAMESPACE:icons"

    /** Base font key of the runtime-uploaded icons. */
    const val UPLOADS_FONT = "$NAMESPACE:uploads"

    /** First codepoint used for icon glyphs (Unicode Private Use Area). */
    const val FIRST_CODEPOINT = 0xE000

    /** Rendered icon size in pixels (square). */
    const val ICON_SIZE = 16

    /** The title-glyph ascent the phone case is drawn at (GUI top = y 0). */
    const val TITLE_ASCENT = 13

    /**
     * Pixel Y (from the GUI top) of each app-grid row's icon top — aligned
     * to chest slot rows 1..4 (`18 + row*18`) so a drawn icon sits in its
     * clickable slot.
     */
    val ROW_TOP_Y = listOf(36, 54, 72, 90)

    /** Codepoint of each built-in icon, matching the baked font. */
    val BUILTIN_CODEPOINTS = mapOf(
        "default" to 0xE000,
        "messages" to 0xE001,
        "navigator" to 0xE002,
        "profile" to 0xE003,
        "settings" to 0xE004,
        "guard" to 0xE005,
        "network" to 0xE006,
    )

    /**
     * The bitmap-font ascent that places a row's icon at [ROW_TOP_Y].
     *
     * @param row the app-grid row index.
     * @return the ascent for that row.
     */
    fun ascentForRow(row: Int): Int = TITLE_ASCENT - ROW_TOP_Y[row.coerceIn(0, ROW_TOP_Y.lastIndex)]
}

/**
 * Runtime registry of uploaded app icons: maps a stable icon id to a
 * codepoint, persists the mapping, writes each PNG plus the per-row bitmap
 * fonts as runtime pack contributions, and triggers a pack rebuild.
 *
 * Every uploaded icon is exposed in [PhoneIcons.ROW_TOP_Y].size fonts named
 * `helix_phone:uploads_row<n>` — the same glyph at a different ascent per
 * home-screen row — so the paper side can draw it on any row.
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
     * Resolves an app's icon reference to its glyph (base font + codepoint).
     * Unknown or missing icons fall back to the built-in default.
     *
     * @param icon the `builtin:<name>` / `custom:<id>` reference.
     * @return the base font key and the one-character glyph.
     */
    fun resolve(icon: String): Pair<String, String> {
        if (icon.startsWith("custom:")) {
            val index = registry[icon.removePrefix("custom:")]
            if (index != null) {
                return PhoneIcons.UPLOADS_FONT to codepoint(PhoneIcons.FIRST_CODEPOINT + index)
            }
        }
        val builtin = icon.removePrefix("builtin:")
        val cp = PhoneIcons.BUILTIN_CODEPOINTS[builtin] ?: PhoneIcons.BUILTIN_CODEPOINTS.getValue("default")
        return PhoneIcons.BUILTIN_FONT to codepoint(cp)
    }

    /**
     * Stores (or replaces) an uploaded icon PNG and regenerates the uploads
     * fonts, then rebuilds the network pack.
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
            registry[iconId] = nextFreeIndex()
            save()
        }
        context.contributePackAsset(pngPath(iconId), bytes)
        regenerateFonts()
        context.rebuildNetworkPack()
        return true
    }

    /**
     * Removes an uploaded icon and its PNG, regenerates the uploads fonts and
     * rebuilds the pack.
     *
     * @param iconId the icon id to remove.
     */
    fun remove(iconId: String) {
        if (registry.remove(iconId) == null) {
            return
        }
        save()
        context.removePackAsset(pngPath(iconId))
        regenerateFonts()
        context.rebuildNetworkPack()
    }

    private fun regenerateFonts() {
        PhoneIcons.ROW_TOP_Y.indices.forEach { row ->
            val providers = registry.entries.sortedBy { it.value }.joinToString(",") { (iconId, index) ->
                """{"type":"bitmap","file":"${PhoneIcons.NAMESPACE}:font/upload_$iconId.png",""" +
                    """"ascent":${PhoneIcons.ascentForRow(row)},"height":${PhoneIcons.ICON_SIZE},""" +
                    """"chars":["${codepoint(PhoneIcons.FIRST_CODEPOINT + index)}"]}"""
            }
            val fontPath = "assets/${PhoneIcons.NAMESPACE}/font/uploads_row$row.json"
            if (registry.isEmpty()) {
                context.removePackAsset(fontPath)
            } else {
                context.contributePackAsset(fontPath, """{"providers":[$providers]}""".encodeToByteArray())
            }
        }
    }

    private fun nextFreeIndex(): Int {
        val used = registry.values.toSet()
        var index = 0
        while (index in used) {
            index += 1
        }
        return index
    }

    private fun pngPath(iconId: String): String =
        "assets/${PhoneIcons.NAMESPACE}/textures/font/upload_$iconId.png"

    private fun codepoint(value: Int): String = String(Character.toChars(value))

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
        /** Storage key of the icon-id to codepoint-index registry. */
        const val STORAGE_KEY = "phone.icons"
    }
}
