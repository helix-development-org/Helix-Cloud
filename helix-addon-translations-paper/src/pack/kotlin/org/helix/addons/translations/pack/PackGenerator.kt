package org.helix.addons.translations.pack

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
 * Draws the translations-editor background — a tiled, darkened Minecraft dirt
 * texture, the classic menu/disconnect-screen look — and assembles the
 * resource pack the Paper component renders as an IGui font glyph.
 *
 * Geometry mirrors the shared IGui convention: a 6-row container GUI is
 * 176x222 px; a glyph rendered in the title at ascent 13 lands with its
 * top-left at the GUI's top-left once the cursor is moved to pixel 0. The
 * per-row text fonts (`helix_guis:text_row_*`) and the invisible cursor
 * spacing glyphs come from the shared Helix-GUIs pack, so this pack only ships
 * the dirt background.
 */
private const val WIDTH = 176
private const val HEIGHT = 222

/** Logical 16x16 dirt tile scaled by this factor into chunky pixels. */
private const val TILE = 16
private const val SCALE = 4

/**
 * Darkened dirt palette (r,g,b) with weights — mostly mid browns, a few dark
 * specks and the occasional lighter grain, pre-darkened toward the in-game
 * menu background (Mojang multiplies the dirt tile by a dark tint).
 */
private val PALETTE = listOf(
    Triple(83, 60, 42) to 5,
    Triple(75, 53, 36) to 6,
    Triple(69, 48, 31) to 5,
    Triple(60, 42, 28) to 3,
    Triple(93, 70, 49) to 2,
    Triple(48, 34, 23) to 2,
)

/**
 * Entry point: writes `pack.zip` to the path given as first argument.
 *
 * @param args `[0]` output path of the pack zip.
 */
fun main(args: Array<String>) {
    val output = Path.of(args.first())
    Files.createDirectories(output.parent)
    val entries = linkedMapOf(
        "pack.mcmeta" to packMeta(),
        "assets/translations/font/gui.json" to guiFont(),
        "assets/translations/textures/font/dirt.png" to dirtBackground(),
    )
    // Hide the vanilla container "Inventory" label so it does not show through
    // the dirt (same trick the BetterMSGs pack uses).
    val emptyInventoryLabel = """{"container.inventory": ""}""".toByteArray()
    entries["assets/minecraft/lang/en_us.json"] = emptyInventoryLabel
    entries["assets/minecraft/lang/de_de.json"] = emptyInventoryLabel
    ZipOutputStream(Files.newOutputStream(output)).use { zip ->
        entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name)
            entry.time = 0L // deterministic zip → stable sha1
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    println("Translations pack written to $output (${Files.size(output)} bytes)")
}

private fun packMeta(): ByteArray =
    """{"pack": {"pack_format": 46, "supported_formats": {"min_inclusive": 34, "max_inclusive": 999}, "description": "Helix translations editor background"}}"""
        .toByteArray()

private fun guiFont(): ByteArray =
    """
    {
      "providers": [
        {"type": "bitmap", "file": "translations:font/dirt.png", "ascent": 13, "height": $HEIGHT,
         "chars": [""]}
      ]
    }
    """.trimIndent().toByteArray()

/** The tiled, darkened dirt background as a 176x222 PNG. */
private fun dirtBackground(): ByteArray {
    val tile = dirtTile()
    val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    // Nearest-neighbour so the scaled dirt stays crisp and pixelated.
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    val step = TILE * SCALE
    var y = 0
    while (y < HEIGHT) {
        var x = 0
        while (x < WIDTH) {
            g.drawImage(tile, x, y, step, step, null)
            x += step
        }
        y += step
    }
    // Dark wash on top for the disconnect-screen look and text contrast.
    g.color = Color(0, 0, 0, 120)
    g.fillRect(0, 0, WIDTH, HEIGHT)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

/** A deterministic 16x16 dirt tile (fixed seed, weighted palette). */
private fun dirtTile(): BufferedImage {
    val bag = PALETTE.flatMap { (rgb, weight) -> List(weight) { rgb } }
    var seed = 0x1337BEEFL
    fun rnd(): Int {
        seed = (seed * 1103515245L + 12345L) and 0x7FFFFFFFL
        return seed.toInt()
    }
    val tile = BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB)
    for (py in 0 until TILE) {
        for (px in 0 until TILE) {
            val (r, gr, b) = bag[rnd() % bag.size]
            tile.setRGB(px, py, Color(r, gr, b).rgb)
        }
    }
    return tile
}
