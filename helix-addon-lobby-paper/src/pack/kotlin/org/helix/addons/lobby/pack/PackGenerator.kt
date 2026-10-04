package org.helix.addons.lobby.pack

import de.tytoss.igui.screen.ScreenFonts
import org.helix.addons.lobby.paper.NavigatorGlyphs
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

private const val NAMESPACE = "helix_lobby"

/**
 * Resource pack of the lobby: the navigator art (Blockbench-painted PNGs in `src/pack/resources/navigator`) as
 * glyphs of `helix_lobby:navigator`, positioned by [NavigatorGlyphs] (board tiles, shields per card row, emblems
 * per card row).
 *
 * @param args `[0]` output path of the pack zip, `[1]` the art directory.
 */
fun main(args: Array<String>) {
    val output = Path.of(args[0])
    val art = Path.of(args[1])
    Files.createDirectories(output.parent)
    val entries = linkedMapOf<String, ByteArray>()
    entries["pack.mcmeta"] = """{"pack":{"pack_format":46,"supported_formats":{"min_inclusive":46,"max_inclusive":999},"description":"Helix Lobby"}}""".toByteArray()
    val providers = mutableListOf<String>()
    val textures = "assets/$NAMESPACE/textures/gui/navigator"

    val bg = ImageIO.read(art.resolve("background.png").toFile())
    require(bg.width == NavigatorGlyphs.BG_W && bg.height == NavigatorGlyphs.BG_H) { "background must be ${NavigatorGlyphs.BG_W}x${NavigatorGlyphs.BG_H}" }
    ScreenFonts.tiles(bg.width, bg.height).forEachIndexed { i, tile ->
        val part = bg.getSubimage(tile.x, tile.y, tile.width, tile.height)
        entries["$textures/background_$i.png"] = png(part)
        providers += ScreenFonts.bitmap("$NAMESPACE:gui/navigator/background_$i.png", NavigatorGlyphs.BG_Y + tile.y, tile.height, NavigatorGlyphs.BG_TILE + i)
    }
    entries["$textures/shield.png"] = Files.readAllBytes(art.resolve("shield.png"))
    for (row in 0 until NavigatorGlyphs.ROWS) {
        providers += ScreenFonts.bitmap("$NAMESPACE:gui/navigator/shield.png", NavigatorGlyphs.shieldTop(row), NavigatorGlyphs.SHIELD_H, NavigatorGlyphs.SHIELD + row)
    }
    NavigatorGlyphs.EMBLEMS.forEachIndexed { e, id ->
        entries["$textures/emblem_$id.png"] = Files.readAllBytes(art.resolve("emblems/$id.png"))
        for (row in 0 until NavigatorGlyphs.ROWS) {
            providers += ScreenFonts.bitmap("$NAMESPACE:gui/navigator/emblem_$id.png", NavigatorGlyphs.emblemTop(row), NavigatorGlyphs.EMBLEM_SIZE, NavigatorGlyphs.emblem(e, row))
        }
    }
    // unsichtbares Item für die Tooltips über den Karten
    entries["assets/$NAMESPACE/items/empty.json"] = """{"model":{"type":"minecraft:empty"}}""".toByteArray()
    entries["assets/$NAMESPACE/font/navigator.json"] = """{"providers":[${providers.joinToString(",")}]}""".toByteArray()

    ZipOutputStream(Files.newOutputStream(output)).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name).apply { time = 0L })
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    println("Lobby pack written to $output")
}

private fun png(image: java.awt.image.BufferedImage): ByteArray =
    ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
