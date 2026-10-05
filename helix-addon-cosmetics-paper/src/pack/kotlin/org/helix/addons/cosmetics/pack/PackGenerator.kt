package org.helix.addons.cosmetics.pack

import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * Assembles the cosmetics resource pack from the Blockbench exports in `src/pack/resources/cosmetics`
 * (JSON models in `models`, PNG textures in `textures`; the editable Blockbench projects live next to them in
 * `blockbench`).
 *
 * The Paper component shows cosmetics as fake equipment, so the client renders them as part of the player model:
 *
 *  - headwear: item definition `helix_cosmetics:<name>` → the Blockbench model, whose `head` display transform
 *    ([HEAD_DISPLAY]) puts the neck-pivoted model on the head
 *  - wings: item definitions `helix_cosmetics:<name>_right` / `_left` → the Blockbench wing models, shown by
 *    item displays mounted client-side on the player; as fallback (no packetevents) the equipment asset
 *    `helix_cosmetics:<name>` with a `wings` layer – the vanilla elytra model with an HD texture generated from
 *    the Blockbench wing art ([elytraTexture]); `<name>_icon` is its inventory icon
 */
private const val NAMESPACE = "helix_cosmetics"

/** Headwear models (item definitions of the same name). */
internal val HEADWEAR = listOf("crown_silver", "crown_bronze", "crown_gold", "halo_white", "halo_dark", "halo_rainbow")

/** Wing themes: models `wings_<id>_right`/`_left`, art in `textures/wings_<id>.png`, elytra asset `wings_<id>`. */
internal val WINGS = listOf("angel", "fairy", "ice", "fire", "demon", "dragon")

/**
 * `head` display of the headwear models. Models are built with 1 unit = 1 skin pixel around the neck (model
 * centre); the head slot renders the model centre at the head centre at 0.625 skin pixels per unit – so scale
 * by 1.6 and move down by 4 skin pixels (6.4 units).
 */
private const val HEAD_DISPLAY = """"display": {"head": {"rotation": [0, 0, 0], "translation": [0, -6.4, 0], "scale": [1.6, 1.6, 1.6]}}"""

/** Elytra texture: vanilla layout 64×32, generated at [ELYTRA_SCALE]× for detail. */
private const val ELYTRA_SCALE = 4

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

    val models = files(source.resolve("models"), "json")
    val textures = files(source.resolve("textures"), "png")
    val required = HEADWEAR + WINGS.flatMap { listOf("wings_${it}_right", "wings_${it}_left") }
    val missing = required.filterNot { it in models } + WINGS.map { "wings_$it" }.filterNot { it in textures }
    require(missing.isEmpty()) { "missing Blockbench exports: $missing" }

    models.forEach { (name, path) ->
        var json = Files.readString(path)
        if (name in HEADWEAR) json = json.replaceFirst("{", "{\n  $HEAD_DISPLAY,")
        entries["assets/$NAMESPACE/models/item/$name.json"] = json.toByteArray()
        Regex("\"$NAMESPACE:item/([a-z0-9_]+)\"").findAll(json).forEach {
            require(it.groupValues[1] in textures) { "model $name references missing texture ${it.groupValues[1]}" }
        }
    }
    textures.forEach { (name, path) -> entries["assets/$NAMESPACE/textures/item/$name.png"] = Files.readAllBytes(path) }

    HEADWEAR.forEach { entries["assets/$NAMESPACE/items/$it.json"] = itemDefinition(it) }
    WINGS.forEach { id ->
        val name = "wings_$id"
        entries["assets/$NAMESPACE/items/${name}_icon.json"] = itemDefinition("${name}_right")
        entries["assets/$NAMESPACE/items/${name}_right.json"] = itemDefinition("${name}_right")
        entries["assets/$NAMESPACE/items/${name}_left.json"] = itemDefinition("${name}_left")
        entries["assets/$NAMESPACE/equipment/$name.json"] =
            """{"layers": {"wings": [{"texture": "$NAMESPACE:$name"}]}}""".toByteArray()
        entries["assets/$NAMESPACE/textures/entity/equipment/wings/$name.png"] =
            elytraTexture(ImageIO.read(textures.getValue(name).toFile()))
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

private fun itemDefinition(model: String): ByteArray =
    """{"model": {"type": "minecraft:model", "model": "$NAMESPACE:item/$model"}}""".toByteArray()

/**
 * Maps the Blockbench wing art (48×60 in the top-left of the 64×64 texture: shoulder/root on the left, leading
 * edge along the top, feathers hanging down) onto the elytra wing box (texture offset 22,0; 10×20×2): the art is
 * turned so the root sits at the shoulder (top) and the tip at the bottom, then drawn onto the back and front
 * faces; sides, top and bottom take a squeezed copy for matching colours.
 *
 * @param art the wing art texture.
 * @return PNG bytes of the elytra texture.
 */
internal fun elytraTexture(art: BufferedImage): ByteArray {
    val s = ELYTRA_SCALE
    val wing = art.getSubimage(0, 0, minOf(48, art.width), minOf(60, art.height))
    // Wurzel nach oben (Schulter), Flügelspitze nach unten, Vorderkante außen
    val turned = BufferedImage(wing.height, wing.width, BufferedImage.TYPE_INT_ARGB)
    turned.createGraphics().apply {
        transform = AffineTransform().apply { translate(wing.height.toDouble(), 0.0); rotate(Math.PI / 2) }
        drawImage(wing, 0, 0, null)
        dispose()
    }
    val out = BufferedImage(64 * s, 32 * s, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    fun draw(x: Int, y: Int, w: Int, h: Int, mirror: Boolean = false) {
        if (mirror) g.drawImage(turned, (x + w) * s, y * s, -w * s, h * s, null)
        else g.drawImage(turned, x * s, y * s, w * s, h * s, null)
    }
    draw(36, 2, 10, 20) // back (seen from behind)
    draw(24, 2, 10, 20, mirror = true) // front
    draw(22, 2, 2, 20) // side
    draw(34, 2, 2, 20) // side
    draw(24, 0, 10, 2) // top
    draw(34, 0, 10, 2) // bottom
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
}

private fun packMeta(): ByteArray =
    """
    {
      "pack": {
        "pack_format": 46,
        "supported_formats": {"min_inclusive": 46, "max_inclusive": 999}, "min_format": 46, "max_format": 999,
        "description": "Helix Cosmetics"
      }
    }
    """.trimIndent().toByteArray()
