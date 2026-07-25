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
    // input bar over the hotbar row: rounded field + send button hint
    g.color = bodyDark
    g.fillRect(0, 194, WIDTH, HEIGHT - 194)
    g.color = surfaceLight
    g.fillRoundRect(6, 197, WIDTH - 32, 20, 9, 9)
    g.color = textDim
    g.fillRect(12, 206, 30, 2)
    g.color = accent
    g.fillRoundRect(WIDTH - 24, 197, 18, 20, 9, 9)
    g.color = Color.WHITE
    g.fillPolygon(intArrayOf(WIDTH - 19, WIDTH - 11, WIDTH - 19), intArrayOf(202, 207, 212), 3)
}

/** The scrollbar thumb, positioned via eight ascent variants. */
private fun drawThumb(g: Graphics2D) {
    g.color = Color(0x50, 0x53, 0x60)
    g.fillRoundRect(0, 0, 4, 20, 3, 3)
    g.color = Color(0x6A, 0x6E, 0x7E)
    g.fillRect(1, 1, 2, 18)
}
