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
 * font ascent 13. Text row 0 re-declares Minecraft's ascii glyphs at the
 * regular title line, plus the spacing font IGui's renderer requires.
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
        "assets/iguard/font/spaces.json" to spacesFont(),
        "assets/iguard/font/ui.json" to uiFont(),
        "assets/iguard/textures/font/header.png" to png(WIDTH, 18, ::drawHeader),
        "assets/iguard/textures/font/background.png" to png(WIDTH, 222, ::drawBackground),
    )
    entries.putAll(textRowFonts())
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

/** IGui's SpacingRenderer glyphs: powers of two, positive and negative. */
private fun spacesFont(): ByteArray {
    val advances = (0..9).flatMap { power ->
        val positive = if (power == 9) 0x0010 else 0x0001 + power
        val negative = if (power == 9) 0x1010 else 0x1001 + power
        listOf(
            "\"\\u%04X\": %d".format(positive, 1 shl power),
            "\"\\u%04X\": %d".format(negative, -(1 shl power)),
        )
    }.joinToString(", ")
    return """{"providers": [{"type": "space", "advances": {$advances}}]}""".toByteArray()
}

private fun uiFont(): ByteArray = (
    """{"providers": [""" +
        """{"type": "bitmap", "file": "iguard:font/header.png", "ascent": 13, "height": 18, "chars": [""]}, """ +
        """{"type": "bitmap", "file": "iguard:font/background.png", "ascent": 13, "height": 222, "chars": [""]}""" +
        """]}"""
    ).toByteArray()

/** Text rows 0..6: vanilla ascii shifted per chest row (row 0 = title line). */
private fun textRowFonts(): Map<String, ByteArray> = (0..6).associate { row ->
    val ascent = 7 - (18 * row)
    "assets/iguard/font/text_row_$row.json" to
        (
            """{"providers": [{"type": "space", "advances": {" ": 4}}, """ +
                """{"type": "bitmap", "file": "minecraft:font/ascii.png", "ascent": $ascent, "height": 8, "chars": [$ASCII_GRID]}]}"""
            ).toByteArray()
}

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

/** The 16x16 character grid of Minecraft's `font/ascii.png`. */
private val ASCII_GRID: String = buildString {
    val rows = listOf(
        "\\u00c0\\u00c1\\u00c2\\u00c8\\u00ca\\u00cb\\u00cd\\u00d3\\u00d4\\u00d5\\u00da\\u00df\\u00e3\\u00f5\\u011f\\u0130",
        "\\u0131\\u0152\\u0153\\u015e\\u015f\\u0174\\u0175\\u017e\\u0207\\u0000\\u0000\\u0000\\u0000\\u0000\\u0000\\u0000",
        "\\u0020\\u0021\\u0022\\u0023\\u0024\\u0025\\u0026\\u0027\\u0028\\u0029\\u002a\\u002b\\u002c\\u002d\\u002e\\u002f",
        "\\u0030\\u0031\\u0032\\u0033\\u0034\\u0035\\u0036\\u0037\\u0038\\u0039\\u003a\\u003b\\u003c\\u003d\\u003e\\u003f",
        "\\u0040\\u0041\\u0042\\u0043\\u0044\\u0045\\u0046\\u0047\\u0048\\u0049\\u004a\\u004b\\u004c\\u004d\\u004e\\u004f",
        "\\u0050\\u0051\\u0052\\u0053\\u0054\\u0055\\u0056\\u0057\\u0058\\u0059\\u005a\\u005b\\u005c\\u005d\\u005e\\u005f",
        "\\u0060\\u0061\\u0062\\u0063\\u0064\\u0065\\u0066\\u0067\\u0068\\u0069\\u006a\\u006b\\u006c\\u006d\\u006e\\u006f",
        "\\u0070\\u0071\\u0072\\u0073\\u0074\\u0075\\u0076\\u0077\\u0078\\u0079\\u007a\\u007b\\u007c\\u007d\\u007e\\u0000",
        "\\u00c7\\u00fc\\u00e9\\u00e2\\u00e4\\u00e0\\u00e5\\u00e7\\u00ea\\u00eb\\u00e8\\u00ef\\u00ee\\u00ec\\u00c4\\u00c5",
        "\\u00c9\\u00e6\\u00c6\\u00f4\\u00f6\\u00f2\\u00fb\\u00f9\\u00ff\\u00d6\\u00dc\\u00f8\\u00a3\\u00d8\\u00d7\\u0192",
        "\\u00e1\\u00ed\\u00f3\\u00fa\\u00f1\\u00d1\\u00aa\\u00ba\\u00bf\\u00ae\\u00ac\\u00bd\\u00bc\\u00a1\\u00ab\\u00bb",
        "\\u2591\\u2592\\u2593\\u2502\\u2524\\u2561\\u2562\\u2556\\u2555\\u2563\\u2551\\u2557\\u255d\\u255c\\u255b\\u2510",
        "\\u2514\\u2534\\u252c\\u251c\\u2500\\u253c\\u255e\\u255f\\u255a\\u2554\\u2569\\u2566\\u2560\\u2550\\u256c\\u2567",
        "\\u2568\\u2564\\u2565\\u2559\\u2558\\u2552\\u2553\\u256b\\u256a\\u2518\\u250c\\u2588\\u2584\\u258c\\u2590\\u2580",
        "\\u03b1\\u03b2\\u0393\\u03c0\\u03a3\\u03c3\\u03bc\\u03c4\\u03a6\\u0398\\u03a9\\u03b4\\u221e\\u2205\\u2208\\u2229",
        "\\u2261\\u00b1\\u2265\\u2264\\u2320\\u2321\\u00f7\\u2248\\u00b0\\u2219\\u00b7\\u221a\\u207f\\u00b2\\u25a0\\u0000",
    )
    append(rows.joinToString(",\n") { "\"$it\"" })
}
