package org.helix.addons.bettermsgs.pack

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
 * Draws every BetterMSGs texture (phone home screen, Discord-style chat,
 * scrollbar thumb) with Java2D and assembles the resource pack consumed by
 * the Paper component via IGui font glyphs.
 *
 * Geometry: a 6-row container GUI is 176x222 px; glyphs rendered in the
 * title at font ascent 13 start at the GUI's top-left when the cursor is
 * moved to pixel 0. Chest slots sit at (8 + col*18, 18 + row*18), the
 * player inventory at y 140/158/176 and the hotbar at y 198.
 */
private const val WIDTH = 176
private const val HEIGHT = 222

private val bodyDark = Color(0x14, 0x15, 0x1B)
private val surface = Color(0x1E, 0x1F, 0x26)
private val surfaceLight = Color(0x2B, 0x2D, 0x36)
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
        "assets/bettermsgs/font/spaces.json" to spacesFont(),
        "assets/bettermsgs/font/gui.json" to guiFont(),
        "assets/bettermsgs/textures/font/home.png" to png(::drawHome),
        "assets/bettermsgs/textures/font/chat.png" to png(::drawChat),
        "assets/bettermsgs/textures/font/thumb.png" to png(4, 20, ::drawThumb),
    )
    entries.putAll(textRowFonts())
    // 2x16 with the visible 2x2 at the top: bitmap ascents may not exceed
    // the glyph height, so the transparent padding buys legal ascent room
    entries["assets/bettermsgs/textures/font/pixel.png"] = png(2, 16) { g ->
        g.color = Color.WHITE
        g.fillRect(0, 0, 2, 2)
    }
    entries["assets/bettermsgs/font/pixels.json"] = pixelFont()
    ZipOutputStream(Files.newOutputStream(output)).use { zip ->
        entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name)
            entry.time = 0L // deterministic zip → stable sha1
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    println("BetterMSGs pack written to $output (${Files.size(output)} bytes)")
}

private fun packMeta(): ByteArray =
    """
    {
      "pack": {
        "pack_format": 46,
        "supported_formats": {"min_inclusive": 34, "max_inclusive": 999},
        "description": "BetterMSGs GUI textures"
      }
    }
    """.trimIndent().toByteArray()

/** IGui's SpacingRenderer glyphs: powers of two, positive and negative. */
private fun spacesFont(): ByteArray {
    val advances = StringBuilder()
    for (power in 0..9) {
        val positive = if (power == 9) 0x0010 else 0x0001 + power
        val negative = if (power == 9) 0x1010 else 0x1001 + power
        advances.append("    \"\\u%04X\": %d,\n".format(positive, 1 shl power))
        advances.append("    \"\\u%04X\": %d,\n".format(negative, -(1 shl power)))
    }
    return """
    {
      "providers": [{
        "type": "space",
        "advances": {
    ${advances.toString().trimEnd(',', '\n').prependIndent()}
        }
      }]
    }
    """.trimIndent().toByteArray()
}

private fun guiFont(): ByteArray {
    val thumbProviders = (0..7).joinToString(",\n") { index ->
        val ascent = 13 - Math.round(index * (178 - 20) / 7.0).toInt()
        """
        {"type": "bitmap", "file": "bettermsgs:font/thumb.png", "ascent": $ascent, "height": 20,
         "chars": ["\uE01$index"]}
        """.trimIndent()
    }
    return """
    {
      "providers": [
        {"type": "bitmap", "file": "bettermsgs:font/home.png", "ascent": 13, "height": $HEIGHT,
         "chars": [""]},
        {"type": "bitmap", "file": "bettermsgs:font/chat.png", "ascent": 13, "height": $HEIGHT,
         "chars": [""]},
    ${thumbProviders.prependIndent("    ")}
      ]
    }
    """.trimIndent().toByteArray()
}

private fun png(draw: (Graphics2D) -> Unit): ByteArray = png(WIDTH, HEIGHT, draw)

private fun png(width: Int, height: Int, draw: (Graphics2D) -> Unit): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    draw(graphics)
    graphics.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

/** The phone: rounded body, status bar, app grid shadows, dock, home bar. */
private fun drawHome(g: Graphics2D) {
    g.color = bodyDark
    g.fillRoundRect(0, 0, WIDTH, HEIGHT, 14, 14)
    g.color = outline
    g.stroke = BasicStroke(1f)
    g.drawRoundRect(0, 0, WIDTH - 1, HEIGHT - 1, 14, 14)
    // screen with a subtle two-tone "wallpaper"
    g.color = surface
    g.fillRoundRect(3, 3, WIDTH - 6, HEIGHT - 6, 10, 10)
    for (band in 0..5) {
        g.color = Color(surfaceLight.red, surfaceLight.green, surfaceLight.blue, 30 - band * 4)
        g.fillArc(-60 + band * 46, HEIGHT - 40 - band * 34, 120, 120, 0, 360)
    }
    // status bar: notch, clock dots, battery
    g.color = bodyDark
    g.fillRoundRect(66, 3, 44, 8, 6, 6)
    g.color = textDim
    g.fillRect(10, 6, 2, 2); g.fillRect(13, 6, 2, 2); g.fillRect(17, 5, 1, 4) // "9:41"-ish dots
    g.drawRect(WIDTH - 22, 5, 10, 4)
    g.fillRect(WIDTH - 21, 6, 6, 2)
    g.fillRect(WIDTH - 11, 6, 1, 2)
    // app tile shadows under the head slots (chest rows 2..5, cols 2..8)
    g.color = Color(0, 0, 0, 70)
    for (row in 1..4) {
        for (col in 1..7) {
            g.fillRoundRect(8 + col * 18 - 1, 18 + row * 18 - 1, 18, 18, 5, 5)
        }
    }
    // header row hint (row 1 stays free for controls)
    g.color = textDim
    g.fillRect(8, 24, 40, 1)
    // dock behind the hotbar + home indicator
    g.color = Color(0xFF, 0xFF, 0xFF, 18)
    g.fillRoundRect(5, 196, WIDTH - 10, 21, 8, 8)
    g.color = textDim
    g.fillRoundRect(WIDTH / 2 - 14, HEIGHT - 4, 28, 2, 2, 2)
    // pagination arrows on chest row 6 (slots 1 and 9), close "x" centered
    g.color = textDim
    g.fillPolygon(intArrayOf(19, 13, 19), intArrayOf(112, 117, 122), 3)
    g.fillPolygon(intArrayOf(157, 163, 157), intArrayOf(112, 117, 122), 3)
    g.color = Color(0xC8, 0x50, 0x50)
    g.stroke = BasicStroke(2f)
    g.drawLine(84, 113, 92, 121)
    g.drawLine(92, 113, 84, 121)
}

/** Discord-style chat: header, message area, input bar, scrollbar track. */
private fun drawChat(g: Graphics2D) {
    g.color = surface
    g.fillRoundRect(0, 0, WIDTH, HEIGHT, 10, 10)
    g.color = outline
    g.drawRoundRect(0, 0, WIDTH - 1, HEIGHT - 1, 10, 10)
    // header bar (chest row 1): darker, with a name placeholder line
    g.color = bodyDark
    g.fillRoundRect(0, 0, WIDTH, 17, 10, 10)
    g.fillRect(0, 10, WIDTH, 7)
    g.color = accent
    g.fillRect(0, 17, WIDTH, 1)
    // message area: alternating row separators for chest rows 2..6 and
    // the player inventory rows — one message per 18px row
    g.color = Color(0xFF, 0xFF, 0xFF, 8)
    for (rowTop in intArrayOf(36, 72, 108, 140, 176)) {
        g.fillRect(4, rowTop, WIDTH - 12, 18)
    }
    // scrollbar track along the right edge of the message area
    g.color = bodyDark
    g.fillRoundRect(WIDTH - 7, 19, 5, 176, 4, 4)
    // header buttons: back "<" on slot (1,1), scroll "▲" on slot (1,9)
    g.color = textDim
    g.fillPolygon(intArrayOf(19, 13, 19), intArrayOf(5, 9, 13), 3)
    g.fillPolygon(intArrayOf(148, 152, 156), intArrayOf(12, 6, 12), 3)
    // input bar over the hotbar row: real buttons drawn on slot centers
    g.color = bodyDark
    g.fillRect(0, 194, WIDTH, HEIGHT - 194)
    // hotbar slots: x = 8 + slot*18 (16px inner), y = 198..214
    button(g, 0) { x, y -> // back
        g.color = textDim
        g.fillPolygon(intArrayOf(x + 10, x + 5, x + 10), intArrayOf(y + 3, y + 8, y + 13), 3)
    }
    button(g, 3) { x, y -> // older (up)
        g.color = textDim
        g.fillPolygon(intArrayOf(x + 3, x + 8, x + 13), intArrayOf(y + 11, y + 4, y + 11), 3)
    }
    // write: wide accent "input field" spanning slot 4
    button(g, 4) { x, y ->
        g.color = accent
        g.fillRoundRect(x - 1, y - 1, 18, 18, 8, 8)
        g.color = Color.WHITE
        g.fillRect(x + 3, y + 11, 9, 2) // pencil base line
        g.fillPolygon(intArrayOf(x + 5, x + 12, x + 10), intArrayOf(y + 10, y + 3, y + 12), 3)
    }
    button(g, 5) { x, y -> // newer (down)
        g.color = textDim
        g.fillPolygon(intArrayOf(x + 3, x + 8, x + 13), intArrayOf(y + 4, y + 11, y + 4), 3)
    }
    button(g, 8) { x, y -> // close "x"
        g.color = Color(0xC8, 0x50, 0x50)
        g.stroke = BasicStroke(2f)
        g.drawLine(x + 4, y + 4, x + 12, y + 12)
        g.drawLine(x + 12, y + 4, x + 4, y + 12)
    }
}

/** Draws a rounded hotbar-button background and its icon at a slot. */
private fun button(g: Graphics2D, slot: Int, icon: (x: Int, y: Int) -> Unit) {
    val x = 8 + slot * 18
    val y = 198
    g.color = surfaceLight
    g.fillRoundRect(x - 1, y - 1, 18, 18, 6, 6)
    icon(x, y)
}

/** The scrollbar thumb, positioned via eight ascent variants. */
private fun drawThumb(g: Graphics2D) {
    g.color = Color(0x50, 0x53, 0x60)
    g.fillRoundRect(0, 0, 4, 20, 3, 3)
    g.color = Color(0x6A, 0x6E, 0x7E)
    g.fillRect(1, 1, 2, 18)
}

/**
 * Vertical pixel positions of the drawn chat text rows: the header line
 * plus eight message rows (chest rows 2..6, then the three player
 * inventory rows).
 */
private val TEXT_ROW_Y = intArrayOf(5, 22, 40, 58, 76, 94, 141, 159, 177)

/**
 * Fonts `bettermsgs:text_row_0..8` re-declare Minecraft's ascii glyphs with
 * shifted ascents, so drawn title text lands on a specific GUI row
 * (row 0 = header, rows 1..8 = message lines).
 */
private fun textRowFonts(): Map<String, ByteArray> = TEXT_ROW_Y.withIndex().associate { (index, y) ->
    // vanilla ascii renders at y≈6 with ascent 7 — shift down by (y - 6)
    val ascent = 7 - (y - 6)
    "assets/bettermsgs/font/text_row_$index.json" to
        ("""{"providers": [{"type": "bitmap", "file": "minecraft:font/ascii.png", """ +
            """"ascent": $ascent, "height": 8, "chars": [$ASCII_GRID]}]}""").toByteArray()
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


/**
 * Head anchor y positions: the header head plus the eight message rows.
 * A drawn head is 16x16 px = 8x8 skin pixels at 2 px each; every pixel
 * sub-row needs its own glyph ascent, so this font declares 9*8 variants
 * of the white 2x2 pixel at codepoints 0xE100 + row*8 + subRow.
 */
private fun pixelFont(): ByteArray {
    val anchors = intArrayOf(1, 19, 37, 55, 73, 91, 141, 159, 177)
    val providers = buildList {
        anchors.forEachIndexed { row, headY ->
            for (sub in 0..7) {
                val ascent = 13 - (headY + sub * 2)
                val char = "\\u%04X".format(0xE100 + row * 8 + sub)
                add(
                    """{"type": "bitmap", "file": "bettermsgs:font/pixel.png", """ +
                        """"ascent": $ascent, "height": 16, "chars": ["$char"]}"""
                )
            }
        }
    }.joinToString(", ")
    return """{"providers": [$providers]}""".toByteArray()
}
