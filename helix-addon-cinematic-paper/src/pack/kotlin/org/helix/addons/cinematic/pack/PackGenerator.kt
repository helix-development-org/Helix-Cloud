package org.helix.addons.cinematic.pack

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Resource pack of HelixCinematic: the fade font `helix_cinematic:fade` used by the cinematic library's
 * transitions – U+E000 is a black square drawn 256 units high (shown as title, ×4 → covers the screen),
 * U+E001 a -1 space so several squares join without gaps.
 *
 * @param args `[0]` output path of the pack zip.
 */
fun main(args: Array<String>) {
    val output = Path.of(args[0])
    Files.createDirectories(output.parent)
    val black = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply {
        createGraphics().apply { color = Color.BLACK; fillRect(0, 0, 16, 16); dispose() }
    }
    val png = ByteArrayOutputStream().also { ImageIO.write(black, "png", it) }.toByteArray()
    val entries = linkedMapOf(
        "pack.mcmeta" to """{"pack": {"pack_format": 46, "supported_formats": {"min_inclusive": 46, "max_inclusive": 999}, "description": "Helix Cinematic"}}""".toByteArray(),
        "assets/helix_cinematic/textures/font/black.png" to png,
        "assets/helix_cinematic/font/fade.json" to """
            {
              "providers": [
                {"type": "bitmap", "file": "helix_cinematic:font/black.png", "height": 256, "ascent": 125, "chars": [""]},
                {"type": "space", "advances": {"": -1}}
              ]
            }
        """.trimIndent().toByteArray(),
    )
    ZipOutputStream(Files.newOutputStream(output)).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name).apply { time = 0L })
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    println("Cinematic pack written to $output")
}
