package org.helix.addons.translations.pack

import java.awt.BasicStroke
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
 * Draws the translations editor's full-window chest background and assembles
 * the resource pack the Paper component renders as an IGui font glyph.
 *
 * Geometry matches the proven IGuard/BetterMSGs pattern: a 6-row chest is
 * 176x222 px; the background bitmap is bound at font ascent 13 and drawn
 * centered in the title, so it overlays the whole window. Chest slots sit at
 * `(8 + col*18, 18 + row*18)`. The generic spacing/text-row fonts come from
 * the shared `helix_guis` pack; this pack only ships the background.
 */
private const val WIDTH = 176
private const val HEIGHT = 222

/** Private-use glyph the background is bound to; built without a char literal so file rewrites can't drop it. */
private val GLYPH: String = String(Character.toChars(0xE000))

private val accent = Color(0x5A, 0x64, 0xEA)
private val outline = Color(0x3A, 0x3D, 0x4A)
private val textDim = Color(0x8A, 0x8E, 0x9C)

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
        "assets/translations/font/ui.json" to uiFont(),
        "assets/translations/textures/font/background.png" to png(::drawBackground),
    )
    ZipOutputStream(Files.newOutputStream(output)).use { zip ->
        entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name)
            entry.time = 0L
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    println("Translations pack written to $output (${Files.size(output)} bytes)")
}

private fun packMeta(): ByteArray =
    """{"pack": {"pack_format": 46, "supported_formats": {"min_inclusive": 34, "max_inclusive": 999}, "description": "Helix translations editor"}}"""
        .toByteArray()

private fun uiFont(): ByteArray =
    """{"providers": [{"type": "bitmap", "file": "translations:font/background.png", "ascent": 13, "height": $HEIGHT, "chars": ["$GLYPH"]}]}"""
        .toByteArray()

private fun png(draw: (Graphics2D) -> Unit): ByteArray {
    val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    draw(g)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

/**
 * The full 176x222 window: tiled dirt darkened, a header strip, a framed
 * preview panel over chest rows 0-1, slot shading for the key-list rows 2-4
 * and the button row 5.
 */
private fun drawBackground(g: Graphics2D) {
    val tile = dirtTile()
    var y = 0
    while (y < HEIGHT) {
        var x = 0
        while (x < WIDTH) { g.drawImage(tile, x, y, 64, 64, null); x += 64 }
        y += 64
    }
    g.color = Color(0, 0, 0, 140)
    g.fillRect(0, 0, WIDTH, HEIGHT)

    // header strip (title line)
    g.color = Color(0x14, 0x15, 0x1B)
    g.fillRect(0, 0, WIDTH, 16)
    g.color = accent
    g.fillRect(0, 16, WIDTH, 1)

    // preview panel over chest rows 0-1 (y ≈ 18..53)
    g.color = Color(0, 0, 0, 110)
    g.fillRoundRect(6, 19, WIDTH - 12, 36, 5, 5)
    g.color = outline
    g.stroke = BasicStroke(1f)
    g.drawRoundRect(6, 19, WIDTH - 13, 35, 5, 5)

    // subtle slot shading for the key-list rows (2..4) and the button row (5)
    g.color = Color(0, 0, 0, 60)
    for (row in 2..5) {
        for (column in 0..8) {
            g.fillRoundRect(8 + column * 18 - 1, 18 + row * 18 - 1, 18, 18, 4, 4)
        }
    }
    // separator above the button row
    g.color = textDim
    g.fillRect(8, 18 + 5 * 18 - 3, WIDTH - 16, 1)
}

/** A deterministic 16x16 dirt tile (fixed seed, weighted palette). */
private fun dirtTile(): BufferedImage {
    val palette = listOf(
        Triple(83, 60, 42), Triple(75, 53, 36), Triple(69, 48, 31),
        Triple(60, 42, 28), Triple(93, 70, 49), Triple(48, 34, 23),
    )
    var seed = 0x1337BEEFL
    val tile = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    for (py in 0 until 16) {
        for (px in 0 until 16) {
            seed = (seed * 1103515245L + 12345L) and 0x7FFFFFFFL
            val (r, gr, b) = palette[(seed % palette.size).toInt()]
            tile.setRGB(px, py, Color(r, gr, b).rgb)
        }
    }
    return tile
}
