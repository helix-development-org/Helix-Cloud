package org.helix.addons.cosmetics.pack

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * Assembles the cosmetics resource pack from the Blockbench exports in `src/pack/resources/cosmetics`
 * (JSON models in `models`, PNG textures in `textures`; the editable Blockbench projects live next to them in `blockbench`)
 * plus the carrier-item `CustomModelData` mapping.
 *
 * All cosmetics share one carrier item ([CARRIER_MODEL], a plain paper item never actually held) so a single
 * `CustomModelData` value selects the model; the item is only ever shown by an item display entity.
 * Wings are two models (right and left wing, [WING_LEFT_OFFSET]) so the Paper component can flap them.
 */
private const val NAMESPACE = "helix_cosmetics"

/** `CustomModelData` of the left wing = right wing's value + this offset (mirrored in the Paper component). */
const val WING_LEFT_OFFSET = 50

/** The vanilla item every cosmetic model overrides via `CustomModelData` — never actually used as paper. */
private const val CARRIER_MODEL = "paper"

/** Every cosmetic model with its `CustomModelData` (must match the addon's `CosmeticCatalog`). */
internal val MODELS: List<Pair<Int, String>> = buildList {
    listOf("angel" to 1001, "fairy" to 1002, "ice" to 1003, "fire" to 1004, "demon" to 1005, "dragon" to 1006)
        .forEach { (id, cmd) ->
            add(cmd to "wings_${id}_right")
            add(cmd + WING_LEFT_OFFSET to "wings_${id}_left")
        }
    add(2001 to "crown_silver")
    add(2002 to "crown_bronze")
    add(2003 to "halo_white")
    add(2004 to "crown_gold")
    add(2005 to "halo_dark")
    add(2006 to "halo_rainbow")
}.sortedBy { it.first }

/**
 * Entry point: writes `pack.zip`.
 *
 * @param args `[0]` output path of the pack zip, `[1]` the Blockbench export directory.
 */
fun main(args: Array<String>) {
    val output = Path.of(args[0])
    val source = Path.of(args[1])
    Files.createDirectories(output.parent)
    val entries = linkedMapOf<String, ByteArray>()
    entries["pack.mcmeta"] = packMeta()
    entries["assets/minecraft/models/item/$CARRIER_MODEL.json"] = carrierOverrides()
    entries["assets/minecraft/items/$CARRIER_MODEL.json"] = carrierItemModel()

    val models = files(source.resolve("models"), "json")
    val textures = files(source.resolve("textures"), "png")
    val missing = MODELS.map { it.second }.filterNot { it in models }
    require(missing.isEmpty()) { "missing Blockbench exports: $missing" }
    models.forEach { (name, path) -> entries["assets/$NAMESPACE/models/item/$name.json"] = Files.readAllBytes(path) }
    textures.forEach { (name, path) -> entries["assets/$NAMESPACE/textures/item/$name.png"] = Files.readAllBytes(path) }
    // jede Texturreferenz eines Modells muss im Pack liegen
    models.forEach { (name, path) ->
        Regex("\"$NAMESPACE:item/([a-z0-9_]+)\"").findAll(Files.readString(path)).forEach {
            require(it.groupValues[1] in textures) { "model $name references missing texture ${it.groupValues[1]}" }
        }
    }

    ZipOutputStream(Files.newOutputStream(output)).use { zip ->
        entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name)
            entry.time = 0L // deterministic zip → stable sha1
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    println("Cosmetics pack written to $output (${Files.size(output)} bytes, ${models.size} models)")
}

private fun files(dir: Path, extension: String): Map<String, Path> =
    Files.list(dir).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == extension }
            .toList()
            .sortedBy { it.name }
            .associateBy { it.nameWithoutExtension }
    }

private fun packMeta(): ByteArray =
    """
    {
      "pack": {
        "pack_format": 46,
        "supported_formats": {"min_inclusive": 9, "max_inclusive": 999},
        "description": "Helix Cosmetics item models"
      }
    }
    """.trimIndent().toByteArray()

/** Maps `CustomModelData` values on [CARRIER_MODEL] to each cosmetic's model (legacy override format). */
private fun carrierOverrides(): ByteArray {
    val overrides = MODELS.joinToString(",\n    ") { (cmd, model) ->
        """{"predicate": {"custom_model_data": $cmd}, "model": "$NAMESPACE:item/$model"}"""
    }
    return """
    {
      "parent": "minecraft:item/generated",
      "textures": {"layer0": "minecraft:item/paper"},
      "overrides": [
        $overrides
      ]
    }
    """.trimIndent().toByteArray()
}

/**
 * The modern (1.21.4+) item-model definition for [CARRIER_MODEL]; vanilla's built-in definition would otherwise
 * win over the legacy [carrierOverrides] on new clients. Kept alongside the legacy file for older clients.
 */
private fun carrierItemModel(): ByteArray {
    val entries = MODELS.joinToString(",\n        ") { (cmd, model) ->
        """{"threshold": $cmd, "model": {"type": "minecraft:model", "model": "$NAMESPACE:item/$model"}}"""
    }
    return """
    {
      "model": {
        "type": "minecraft:range_dispatch",
        "property": "minecraft:custom_model_data",
        "index": 0,
        "fallback": {"type": "minecraft:model", "model": "minecraft:item/paper"},
        "entries": [
        $entries
        ]
      }
    }
    """.trimIndent().toByteArray()
}
