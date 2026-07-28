package org.helix.addons.cosmetics.pack

import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Draws every cosmetic's texture with Java2D and writes the hand-authored
 * item models (wings: two flat panels spread left/right; crowns: a square
 * band with four corner spikes; halos: a flat square ring) plus the
 * carrier-item `CustomModelData` overrides, then assembles the resource
 * pack consumed by the Paper component's item display entities.
 *
 * All cosmetics share one carrier item ([CARRIER_MODEL], a plain paper
 * item never actually equipped or used) so a single `CustomModelData`
 * value alone selects which cosmetic's model renders — the item is only
 * ever shown floating in an item display entity, never really held.
 */
private const val NAMESPACE = "helix_cosmetics"
private const val TEXTURE_SIZE = 32

/**
 * Entry point: writes `pack.zip` to the path given as first argument.
 *
 * @param args `[0]` output path of the pack zip.
 */
fun main(args: Array<String>) {
    val output = Path.of(args.first())
    Files.createDirectories(output.parent)
    val entries = linkedMapOf<String, ByteArray>()
    entries["pack.mcmeta"] = packMeta()
    entries["assets/minecraft/models/item/$CARRIER_MODEL.json"] = carrierOverrides()

    WingTheme.entries.forEach { theme ->
        entries["assets/$NAMESPACE/models/item/wings_${theme.id}.json"] = wingsModel(theme.id)
        entries["assets/$NAMESPACE/textures/item/wings_${theme.id}.png"] = png { g -> theme.draw(g) }
    }
    CrownTheme.entries.forEach { theme ->
        entries["assets/$NAMESPACE/models/item/crown_${theme.id}.json"] = crownModel(theme.id)
        entries["assets/$NAMESPACE/textures/item/crown_${theme.id}.png"] = png { g -> theme.draw(g) }
    }
    HaloTheme.entries.forEach { theme ->
        entries["assets/$NAMESPACE/models/item/halo_${theme.id}.json"] = haloModel(theme.id)
        entries["assets/$NAMESPACE/textures/item/halo_${theme.id}.png"] = png { g -> theme.draw(g) }
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
    println("Cosmetics pack written to $output (${Files.size(output)} bytes)")
}

/** The vanilla item every cosmetic model overrides via `CustomModelData` — never actually used as paper. */
private const val CARRIER_MODEL = "paper"

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

/** Maps `CustomModelData` values on [CARRIER_MODEL] to each cosmetic's model. */
private fun carrierOverrides(): ByteArray {
    val overrides = buildList {
        WingTheme.entries.forEach { add(it.customModelData to "wings_${it.id}") }
        CrownTheme.entries.forEach { add(it.customModelData to "crown_${it.id}") }
        HaloTheme.entries.forEach { add(it.customModelData to "halo_${it.id}") }
    }.joinToString(",\n    ") { (cmd, model) ->
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
 * Two flat panels spread left and right from a central gap — a simple,
 * axis-aligned silhouette that reads as wings regardless of which way the
 * item display entity is rotated to face, since the plugin orients the
 * whole entity at runtime rather than relying on model-space "forward".
 */
private fun wingsModel(textureId: String): ByteArray = itemModel(
    texture = "wings_$textureId",
    elements = listOf(
        cuboid(from = listOf(-16, 4, -1), to = listOf(-1, 20, 0)),
        cuboid(from = listOf(1, 4, -1), to = listOf(16, 20, 0)),
    ),
)

/** A square band around the head with four corner spikes. */
private fun crownModel(textureId: String): ByteArray = itemModel(
    texture = "crown_$textureId",
    elements = listOf(
        cuboid(from = listOf(4, 8, 4), to = listOf(12, 10, 12)), // band
        cuboid(from = listOf(4, 10, 4), to = listOf(5, 13, 5)),
        cuboid(from = listOf(11, 10, 4), to = listOf(12, 13, 5)),
        cuboid(from = listOf(4, 10, 11), to = listOf(5, 13, 12)),
        cuboid(from = listOf(11, 10, 11), to = listOf(12, 13, 12)),
    ),
)

/** A flat square ring floating above the head (a border frame with an open center). */
private fun haloModel(textureId: String): ByteArray = itemModel(
    texture = "halo_$textureId",
    elements = listOf(
        cuboid(from = listOf(3, 8, 3), to = listOf(13, 9, 5)),
        cuboid(from = listOf(3, 8, 11), to = listOf(13, 9, 13)),
        cuboid(from = listOf(3, 8, 5), to = listOf(5, 9, 11)),
        cuboid(from = listOf(11, 8, 5), to = listOf(13, 9, 11)),
    ),
)

private fun cuboid(from: List<Int>, to: List<Int>): String {
    val face = """{"uv": [0, 0, 16, 16], "texture": "#0"}"""
    return """
        {
          "from": [${from.joinToString(", ")}],
          "to": [${to.joinToString(", ")}],
          "faces": {"north": $face, "south": $face, "east": $face, "west": $face, "up": $face, "down": $face}
        }
    """.trimIndent()
}

private fun itemModel(texture: String, elements: List<String>): ByteArray = """
    {
      "parent": "minecraft:item/generic",
      "textures": {"0": "$NAMESPACE:item/$texture", "particle": "$NAMESPACE:item/$texture"},
      "elements": [
        ${elements.joinToString(",\n        ")}
      ]
    }
""".trimIndent().toByteArray()

/** Renders a [TEXTURE_SIZE]x[TEXTURE_SIZE] texture and encodes it as PNG bytes. */
private fun png(draw: (Graphics2D) -> Unit): ByteArray {
    val image = BufferedImage(TEXTURE_SIZE, TEXTURE_SIZE, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    draw(g)
    g.dispose()
    val bytes = ByteArrayOutputStream()
    ImageIO.write(image, "png", bytes)
    return bytes.toByteArray()
}

private fun Graphics2D.fill(color: Color, x: Int, y: Int, w: Int, h: Int) {
    this.color = color
    fillRect(x, y, w, h)
}

/** Wing texture themes: a base feather color plus a lighter tip stripe. */
private enum class WingTheme(val id: String, val customModelData: Int, val base: Color, val tip: Color) {
    ANGEL("angel", 1001, Color(0xF5, 0xF5, 0xF0), Color(0xFF, 0xD7, 0x00)),
    FAIRY("fairy", 1002, Color(0x9B, 0x5D, 0xE0), Color(0xE0, 0xB8, 0xFF)),
    ICE("ice", 1003, Color(0xA8, 0xE0, 0xF5), Color(0xFF, 0xFF, 0xFF)),
    FIRE("fire", 1004, Color(0xD1, 0x30, 0x0A), Color(0xFF, 0xA5, 0x00)),
    DEMON("demon", 1005, Color(0x20, 0x14, 0x22), Color(0x8B, 0x00, 0x00)),
    DRAGON("dragon", 1006, Color(0x1E, 0x5A, 0x2E), Color(0x8F, 0xD9, 0x6B)),
    ;

    fun draw(g: Graphics2D) {
        g.fill(base, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE)
        // A simple feather-row pattern: alternating lighter stripes.
        for (row in 0 until 4) {
            val y = row * 8
            g.fill(tip, 0, y, TEXTURE_SIZE, 3)
        }
    }
}

/** Crown texture themes: a metal band color plus a gem accent. */
private enum class CrownTheme(val id: String, val customModelData: Int, val metal: Color, val gem: Color) {
    SILVER("silver", 2001, Color(0xC0, 0xC0, 0xC8), Color(0x40, 0x80, 0xFF)),
    BRONZE("bronze", 2002, Color(0xCD, 0x7F, 0x32), Color(0x8B, 0x00, 0x00)),
    GOLD("gold", 2004, Color(0xFF, 0xD7, 0x00), Color(0xFF, 0x00, 0x00)),
    ;

    fun draw(g: Graphics2D) {
        g.fill(metal, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE)
        g.fill(gem, TEXTURE_SIZE / 2 - 3, TEXTURE_SIZE / 2 - 3, 6, 6)
    }
}

/** Halo texture themes: a ring color plus a glow tint. */
private enum class HaloTheme(val id: String, val customModelData: Int, val ring: Color, val glow: Color) {
    WHITE("white", 2003, Color(0xFF, 0xFF, 0xFF), Color(0xFF, 0xFA, 0xC8)),
    DARK("dark", 2005, Color(0x2A, 0x2A, 0x2E), Color(0x8B, 0x00, 0x8B)),
    RAINBOW("rainbow", 2006, Color(0xFF, 0x40, 0x40), Color(0x40, 0x80, 0xFF)),
    ;

    fun draw(g: Graphics2D) {
        g.fill(ring, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE)
        g.fill(glow, 0, TEXTURE_SIZE / 2 - 2, TEXTURE_SIZE, 4)
    }
}
