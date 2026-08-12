package org.helix.addons.phone.pack

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Draws the Helix-Phone resource pack deterministically: the 176×222 phone
 * case glyph, the built-in 16×16 app icons and the per-row bitmap fonts that
 * place an icon in each home-screen slot row. The uploaded-icon fonts are
 * generated at runtime by the node; this covers only the shipped defaults.
 *
 * The single argument is the output `pack.zip` path.
 */
fun main(args: Array<String>) {
    val output = Path.of(args[0])
    Files.createDirectories(output.parent)
    val entries = sortedMapOf<String, ByteArray>()

    entries["pack.mcmeta"] =
        """{"pack":{"pack_format":46,"supported_formats":[34,999],"description":"Helix phone"}}"""
            .encodeToByteArray()

    // Phone case: one 176x222 glyph that overlays the whole inventory.
    entries["assets/helix_phone/textures/font/case.png"] = png(WIDTH, HEIGHT, ::drawCase)
    entries["assets/helix_phone/font/gui.json"] =
        """{"providers":[{"type":"bitmap","file":"helix_phone:font/case.png","ascent":13,"height":222,"chars":[""]}]}"""
            .encodeToByteArray()

    // Built-in icons and their per-row fonts.
    ICONS.forEach { (name, draw) ->
        entries["assets/helix_phone/textures/font/icon_$name.png"] = png(ICON, ICON, draw)
    }
    ROW_TOP_Y.indices.forEach { row ->
        val ascent = TITLE_ASCENT - ROW_TOP_Y[row]
        val providers = ICONS.entries.joinToString(",") { (name, _) ->
            val codepoint = 0xE000 + ICON_ORDER.indexOf(name)
            """{"type":"bitmap","file":"helix_phone:font/icon_$name.png","ascent":$ascent,"height":$ICON,""" +
                """"chars":["${String(Character.toChars(codepoint))}"]}"""
        }
        entries["assets/helix_phone/font/icons_row$row.json"] = """{"providers":[$providers]}""".encodeToByteArray()
    }

    // Hide the vanilla "Inventory" label behind the drawn case.
    entries["assets/minecraft/lang/en_us.json"] = """{"container.inventory":""}""".encodeToByteArray()
    entries["assets/minecraft/lang/de_de.json"] = """{"container.inventory":""}""".encodeToByteArray()

    ZipOutputStream(Files.newOutputStream(output)).use { zip ->
        entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name)
            entry.time = 0
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

/** Renders a transparent ARGB image and returns its PNG bytes. */
private fun png(width: Int, height: Int, draw: (Graphics2D) -> Unit): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    draw(g)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}

private fun drawCase(g: Graphics2D) {
    // Body.
    g.color = Color(0x14, 0x15, 0x1B)
    g.fillRoundRect(0, 0, WIDTH, HEIGHT, 24, 24)
    // Screen inset.
    g.color = Color(0x1E, 0x1F, 0x26)
    g.fillRoundRect(6, 16, WIDTH - 12, HEIGHT - 48, 16, 16)
    // Status bar with a notch.
    g.color = Color(0x2B, 0x2D, 0x36)
    g.fillRoundRect(6, 4, WIDTH - 12, 12, 8, 8)
    g.color = Color(0x14, 0x15, 0x1B)
    g.fillRoundRect(WIDTH / 2 - 18, 4, 36, 7, 6, 6)
    // Home indicator.
    g.color = Color(0x3A, 0x3D, 0x4A)
    g.fillRoundRect(WIDTH / 2 - 28, HEIGHT - 26, 56, 5, 4, 4)
}

// ---- 16x16 icon drawings (transparent background, bright foreground) ----

private fun drawDefault(g: Graphics2D) {
    g.color = Color(0x8A, 0x8E, 0x9C)
    g.fillRoundRect(2, 2, 12, 12, 4, 4)
}

private fun drawMessages(g: Graphics2D) {
    g.color = Color(0x5A, 0x64, 0xEA)
    g.fillRoundRect(1, 2, 14, 10, 4, 4)
    g.fillPolygon(intArrayOf(4, 8, 4), intArrayOf(11, 11, 15), 3)
    g.color = Color.WHITE
    g.fillRect(4, 6, 8, 1)
    g.fillRect(4, 8, 5, 1)
}

private fun drawNavigator(g: Graphics2D) {
    g.color = Color(0x2B, 0x2D, 0x36)
    g.fillOval(1, 1, 14, 14)
    g.color = Color(0xF5, 0x55, 0x55)
    g.fillPolygon(intArrayOf(8, 11, 8), intArrayOf(3, 8, 8), 3)
    g.color = Color.WHITE
    g.fillPolygon(intArrayOf(8, 5, 8), intArrayOf(13, 8, 8), 3)
}

private fun drawProfile(g: Graphics2D) {
    g.color = Color(0x5F, 0xD0, 0xE0)
    g.fillOval(5, 2, 6, 6)
    g.fillRoundRect(2, 9, 12, 6, 5, 5)
}

private fun drawSettings(g: Graphics2D) {
    g.color = Color(0xAA, 0xAE, 0xBC)
    g.fillOval(2, 2, 12, 12)
    g.color = Color(0x1E, 0x1F, 0x26)
    g.fillOval(6, 6, 4, 4)
    g.color = Color(0xAA, 0xAE, 0xBC)
    g.fillRect(7, 0, 2, 4)
    g.fillRect(7, 12, 2, 4)
    g.fillRect(0, 7, 4, 2)
    g.fillRect(12, 7, 4, 2)
}

private fun drawGuard(g: Graphics2D) {
    g.color = Color(0xF5, 0x55, 0x55)
    g.fillPolygon(intArrayOf(8, 14, 14, 8, 2, 2), intArrayOf(1, 3, 9, 15, 9, 3), 6)
    g.color = Color.WHITE
    g.stroke = BasicStroke(2f)
    g.drawPolyline(intArrayOf(5, 7, 11), intArrayOf(8, 11, 5), 3)
}

private fun drawNetwork(g: Graphics2D) {
    g.color = Color(0x5F, 0xE0, 0x8A)
    g.fillOval(1, 1, 14, 14)
    g.color = Color(0x14, 0x15, 0x1B)
    g.stroke = BasicStroke(1f)
    g.drawOval(5, 1, 6, 14)
    g.drawLine(1, 8, 15, 8)
    g.drawLine(3, 4, 13, 4)
    g.drawLine(3, 12, 13, 12)
}

/** Icon draw order — the index is the icon's codepoint offset from U+E000. */
private val ICON_ORDER = listOf("default", "messages", "navigator", "profile", "settings", "guard", "network")

/** Icon name to its draw function. */
private val ICONS: Map<String, (Graphics2D) -> Unit> = linkedMapOf(
    "default" to ::drawDefault,
    "messages" to ::drawMessages,
    "navigator" to ::drawNavigator,
    "profile" to ::drawProfile,
    "settings" to ::drawSettings,
    "guard" to ::drawGuard,
    "network" to ::drawNetwork,
)

/** Pixel Y (from the GUI top) of each app-grid row, aligned to chest rows 1..4. */
private val ROW_TOP_Y = listOf(36, 54, 72, 90)

/** Title-glyph ascent for the GUI top. */
private const val TITLE_ASCENT = 13

/** Phone case width. */
private const val WIDTH = 176

/** Phone case height (overlays chest + player inventory + hotbar). */
private const val HEIGHT = 222

/** Built-in icon size in pixels (square). */
private const val ICON = 16
