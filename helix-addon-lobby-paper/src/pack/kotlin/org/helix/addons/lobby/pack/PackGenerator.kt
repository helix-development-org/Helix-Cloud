package org.helix.addons.lobby.pack

import de.tytoss.igui.screen.ScreenFonts
import de.tytoss.igui.screen.ScreenSprites
import org.helix.addons.lobby.paper.NavigatorGlyphs
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

private const val NAMESPACE = "helix_lobby"

/**
 * Resource pack of the lobby: the navigator map (Blockbench-painted PNGs in `src/pack/resources/navigator`) as glyphs
 * of `helix_lobby:navigator`, positioned by [NavigatorGlyphs] (board tiles, a plaque, name banners and a fence per
 * place), and the emblems as animated, shifted item models (normal and grey maintenance variant).
 *
 * @param args `[0]` output path of the pack zip, `[1]` the art directory.
 */
fun main(args: Array<String>) {
    val output = Path.of(args[0])
    val art = Path.of(args[1])
    Files.createDirectories(output.parent)
    val entries = linkedMapOf<String, ByteArray>()
    entries["pack.mcmeta"] = """{"pack":{"pack_format":46,"supported_formats":{"min_inclusive":46,"max_inclusive":999}, "min_format": 46, "max_format": 999,"description":"Lobby"}}""".toByteArray()
    val providers = mutableListOf<String>()
    val textures = "assets/$NAMESPACE/textures/gui/navigator"

    val bg = ImageIO.read(art.resolve("map.png").toFile())
    require(bg.width == NavigatorGlyphs.BG_W && bg.height == NavigatorGlyphs.BG_H) { "map must be ${NavigatorGlyphs.BG_W}x${NavigatorGlyphs.BG_H}" }
    ScreenFonts.tiles(bg.width, bg.height).forEachIndexed { i, tile ->
        entries["$textures/background_$i.png"] = png(bg.getSubimage(tile.x, tile.y, tile.width, tile.height))
        providers += ScreenFonts.bitmap("$NAMESPACE:gui/navigator/background_$i.png", NavigatorGlyphs.BG_Y + tile.y, tile.height, NavigatorGlyphs.BG_TILE + i)
    }
    entries["$textures/plaque.png"] = Files.readAllBytes(art.resolve("plaque.png"))
    entries["$textures/fence.png"] = Files.readAllBytes(art.resolve("fence.png"))
    NavigatorGlyphs.BAND_STATES.forEach { state -> entries["$textures/band_$state.png"] = Files.readAllBytes(art.resolve("band_$state.png")) }
    NavigatorGlyphs.PLACES.forEachIndexed { i, place ->
        val half = NavigatorGlyphs.PLAQUE_SIZE / 2
        providers += ScreenFonts.bitmap("$NAMESPACE:gui/navigator/plaque.png", place.centerY - half, NavigatorGlyphs.PLAQUE_SIZE, NavigatorGlyphs.PLAQUE + i)
        NavigatorGlyphs.BAND_STATES.forEachIndexed { s, state ->
            providers += ScreenFonts.bitmap("$NAMESPACE:gui/navigator/band_$state.png", place.bandTop, NavigatorGlyphs.BAND_H, NavigatorGlyphs.BAND + i * 3 + s)
        }
        providers += ScreenFonts.bitmap("$NAMESPACE:gui/navigator/fence.png", place.centerY - half - 2, NavigatorGlyphs.FENCE_SIZE, NavigatorGlyphs.FENCE + i)
    }
    // emblems: animated items (Blockbench frame strips + .mcmeta) and the static grey maintenance variant
    NavigatorGlyphs.EMBLEMS.forEachIndexed { e, id ->
        listOf(false, true).forEach { grey ->
            val sprite = NavigatorGlyphs.emblemSprite(e, grey)
            val texture = "assets/$NAMESPACE/textures/item/${sprite.texture}.png"
            if (grey) {
                entries[texture] = Files.readAllBytes(art.resolve("emblems_grey/$id.png"))
            } else {
                entries[texture] = Files.readAllBytes(art.resolve("emblems_anim/$id.png"))
                entries["$texture.mcmeta"] = Files.readAllBytes(art.resolve("emblems_anim/$id.png.mcmeta"))
            }
            entries["assets/$NAMESPACE/models/item/${sprite.model}.json"] = ScreenSprites.modelJson(NAMESPACE, sprite).toByteArray()
            entries["assets/$NAMESPACE/items/${sprite.model}.json"] = ScreenSprites.itemJson(NAMESPACE, sprite).toByteArray()
        }
    }
    // unsichtbares Item für die Tooltips über den Orten
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
