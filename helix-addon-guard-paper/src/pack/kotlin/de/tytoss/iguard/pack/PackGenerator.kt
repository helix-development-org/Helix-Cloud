package de.tytoss.iguard.pack

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
 * Draws the IGuard panel textures (header bar, full-window background)
 * with Java2D and assembles the `iguard` namespace resource pack that the
 * node merges into the network pack.
 *
 * Glyph geometry matches the definitions in GuiService: `` header
 * 176x18, `` background 176x222, both anchored at the GUI top via
 * font ascent 13. The generic font mechanics (spacing glyphs, standard
 * text-row fonts) live in the shared `helix_guis` namespace instead — see
 * helix-addon-guis-paper's own pack generator — so this pack only ships
 * IGuard's own decorative bitmaps.
 */
private const val WIDTH = 176

private val bodyDark = Color(0x12, 0x14, 0x1A)
private val surface = Color(0x1C, 0x1F, 0x27)
private val accent = Color(0xE0, 0x4A, 0x4A)
private val outline = Color(0x37, 0x3B, 0x47)
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
        "assets/iguard/font/ui.json" to uiFont(),
        "assets/iguard/textures/font/header.png" to png(WIDTH, 18, ::drawHeader),
        "assets/iguard/textures/font/background.png" to png(WIDTH, 222, ::drawBackground),
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
    println("IGuard pack written to $output (${Files.size(output)} bytes)")
}

private fun packMeta(): ByteArray =
    """{"pack": {"pack_format": 46, "supported_formats": {"min_inclusive": 34, "max_inclusive": 999}, "description": "IGuard panel textures"}}""".toByteArray()

private fun uiFont(): ByteArray = (
    """{"providers": [""" +
        """{"type": "bitmap", "file": "iguard:font/header.png", "ascent": 13, "height": 18, "chars": [""]}, """ +
        """{"type": "bitmap", "file": "iguard:font/background.png", "ascent": 13, "height": 222, "chars": [""]}""" +
        """]}"""
    ).toByteArray()

private fun png(width: Int, height: Int, draw: (Graphics2D) -> Unit): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    draw(graphics)
    graphics.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

/** The slim header bar: dark strip, shield mark, accent underline. */
private fun drawHeader(g: Graphics2D) {
    g.color = bodyDark
    g.fillRoundRect(0, 0, WIDTH, 17, 8, 8)
    g.fillRect(0, 10, WIDTH, 7)
    g.color = accent
    g.fillRect(0, 17, WIDTH, 1)
    // small shield mark on the left
    g.color = accent
    g.fillPolygon(intArrayOf(10, 16, 16, 13, 10), intArrayOf(4, 4, 10, 14, 10), 5)
    g.color = Color.WHITE
    g.drawLine(12, 8, 13, 10)
    g.drawLine(13, 10, 15, 6)
}

/** The full-window panel: dark surface with header area and grid shading. */
private fun drawBackground(g: Graphics2D) {
    g.color = surface
    g.fillRoundRect(0, 0, WIDTH, 222, 10, 10)
    g.color = outline
    g.stroke = BasicStroke(1f)
    g.drawRoundRect(0, 0, WIDTH - 1, 221, 10, 10)
    g.color = bodyDark
    g.fillRoundRect(0, 0, WIDTH, 17, 10, 10)
    g.fillRect(0, 10, WIDTH, 7)
    g.color = accent
    g.fillRect(0, 17, WIDTH, 1)
    // subtle tile shading behind the chest slot grid
    g.color = Color(0, 0, 0, 60)
    for (row in 1..5) {
        for (column in 0..8) {
            g.fillRoundRect(8 + column * 18 - 1, 18 + row * 18 - 1, 18, 18, 4, 4)
        }
    }
    // separator above the player inventory area
    g.color = textDim
    g.fillRect(8, 136, WIDTH - 16, 1)
}
