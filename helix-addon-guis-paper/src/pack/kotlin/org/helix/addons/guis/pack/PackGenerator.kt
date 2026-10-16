package org.helix.addons.guis.pack

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Assembles the `helix_guis` namespace resource pack: the generic font
 * mechanics [de.tytoss.igui.display.DisplayBuilder]'s title renderer needs
 * (the invisible cursor-spacing glyphs and the standard per-row vanilla-
 * ascii text fonts) — every dependent addon's menu shares these instead of
 * each generating its own copy, which is what let one of them (the profile
 * menu) forget to generate them at all.
 */
private const val NAMESPACE = "helix_guis"

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
        "assets/$NAMESPACE/font/spaces.json" to spacesFont(),
    )
    entries.putAll(textRowFonts())
    entries.putAll(ledger(Path.of(args[1])))
    entries.putAll(wordmark(Path.of(args[1]).resolveSibling("wordmark")))
    entries.putAll(icons(Path.of(args[1]).resolveSibling("icons")))
    entries.putAll(sprites(NAMESPACE, Path.of(args[1]).resolveSibling("anim"), de.tytoss.igui.screen.LedgerLayout.SPRITES))
    entries["assets/$NAMESPACE/items/empty.json"] = """{"model":{"type":"minecraft:empty"}}""".toByteArray()
    // Text at any height for full-window screens (helix-gui `textAt`): one font per text top
    de.tytoss.igui.screen.ScreenFonts.TEXT_TOPS.forEach { top ->
        val key = de.tytoss.igui.screen.ScreenFonts.textFont(top).substringAfter(':')
        entries["assets/$NAMESPACE/font/$key.json"] = de.tytoss.igui.screen.ScreenFonts.textFontJson(top).toByteArray()
    }
    // Full-window GUIs (drawn over the player inventory, see helix-gui `playerInventory`) need the vanilla
    // container "Inventory" label hidden; provided once here for every addon instead of per addon.
    val emptyInventoryLabel = """{"container.inventory": ""}""".toByteArray()
    entries["assets/minecraft/lang/en_us.json"] = emptyInventoryLabel
    entries["assets/minecraft/lang/de_de.json"] = emptyInventoryLabel
    entries.putAll(sounds(Path.of(args[1]).resolveSibling("sounds")))
    ZipOutputStream(Files.newOutputStream(output)).use { zip ->
        entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name)
            entry.time = 0L
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    println("Helix-GUIs pack written to $output (${Files.size(output)} bytes)")
}

private fun packMeta(): ByteArray =
    """{"pack": {"pack_format": 46, "supported_formats": {"min_inclusive": 34, "max_inclusive": 999}, "min_format": 34, "max_format": 999, "description": "Helix-GUIs shared fonts"}}""".toByteArray()

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

/** Text rows 0..6: vanilla ascii shifted per chest row (row 0 = title line). */
private fun textRowFonts(): Map<String, ByteArray> = (0..6).associate { row ->
    val ascent = 7 - (18 * row)
    "assets/$NAMESPACE/font/text_row_$row.json" to
        (
            """{"providers": [{"type": "space", "advances": {" ": 4}}, """ +
                """{"type": "bitmap", "file": "minecraft:font/ascii.png", "ascent": $ascent, "height": 8, "chars": [$ASCII_GRID]}]}"""
            ).toByteArray()
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
 * The shared ledger screen (helix-gui `LedgerScreen`): Blockbench art from `src/pack/resources/ledger` as glyphs of
 * `helix_guis:ledger`, positioned by `LedgerLayout` – board tiles, arrows, buttons, the selected-row strip and the
 * badge pills per row, the empty-state speaker heads from `src/pack/resources/villagers`.
 */
private fun ledger(art: Path): Map<String, ByteArray> {
    val L = de.tytoss.igui.screen.LedgerLayout
    val F = de.tytoss.igui.screen.ScreenFonts
    val out = linkedMapOf<String, ByteArray>()
    val providers = mutableListOf<String>()
    val tex = "assets/$NAMESPACE/textures/gui/ledger"
    fun copy(name: String) { out["$tex/$name.png"] = Files.readAllBytes(art.resolve("$name.png")) }
    val bg = javax.imageio.ImageIO.read(art.resolve("background.png").toFile())
    F.tiles(bg.width, bg.height).forEachIndexed { i, tile ->
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(bg.getSubimage(tile.x, tile.y, tile.width, tile.height), "png", it) }.toByteArray()
        out["$tex/background_$i.png"] = bytes
        providers += F.bitmap("$NAMESPACE:gui/ledger/background_$i.png", L.BG_Y + tile.y, tile.height, L.BG_TILE + i)
    }
    listOf("arrow_back" to L.ARROW_BACK, "arrow_left" to L.ARROW_LEFT, "arrow_right" to L.ARROW_RIGHT,
        "arrow_left_off" to L.ARROW_LEFT_OFF, "arrow_right_off" to L.ARROW_RIGHT_OFF).forEach { (name, code) ->
        copy(name)
        providers += F.bitmap("$NAMESPACE:gui/ledger/$name.png", L.FOOTER_TOP, L.ARROW_SIZE, code)
    }
    L.BUTTON_FILES.forEachIndexed { style, file ->
        listOf(L.BUTTON_SMALL_W to L.BUTTON_SMALL, L.BUTTON_WIDE_W to L.BUTTON_WIDE).forEach { (width, base) ->
            val name = "button_${file}_$width"
            copy(name)
            providers += F.bitmap("$NAMESPACE:gui/ledger/$name.png", L.FOOTER_TOP, L.BUTTON_H, base + style)
        }
    }
    copy("row_selected")
    val pieces = listOf("l", "r") + L.PILL_PIECES.map { it.toString() }
    L.PILL_FILES.forEach { color -> pieces.forEach { copy("pill_${color}_$it") } }
    for (row in 0 until L.ROWS) {
        providers += F.bitmap("$NAMESPACE:gui/ledger/row_selected.png", L.rowTop(row) - 1, 19, L.ROW_SELECTED + row)
        val pillTop = L.rowTop(row) + L.ROW_TEXT_OFFSET - 1
        L.PILL_FILES.forEachIndexed { color, file ->
            pieces.forEachIndexed { part, piece ->
                providers += F.bitmap("$NAMESPACE:gui/ledger/pill_${file}_$piece.png", pillTop, L.PILL_H, L.PILL + row * 32 + color * 8 + part)
            }
        }
    }
    val villagers = art.resolveSibling("villagers")
    de.tytoss.igui.screen.Villagers.IDS.forEachIndexed { i, id ->
        out["$tex/speaker_$id.png"] = Files.readAllBytes(villagers.resolve("${id}_32.png"))
        providers += F.bitmap("$NAMESPACE:gui/ledger/speaker_$id.png", L.EMPTY_HEAD_TOP, L.EMPTY_HEAD_SIZE, L.EMPTY_HEAD + i)
        val model = de.tytoss.igui.screen.Villagers.headModel(id)
        out["assets/$NAMESPACE/textures/item/$model.png"] = Files.readAllBytes(villagers.resolve("${id}_16.png"))
        out["assets/$NAMESPACE/models/item/$model.json"] = """{"parent":"minecraft:item/generated","textures":{"layer0":"$NAMESPACE:item/$model"}}""".toByteArray()
        out["assets/$NAMESPACE/items/$model.json"] = """{"model":{"type":"minecraft:model","model":"$NAMESPACE:item/$model"}}""".toByteArray()
    }
    out["assets/$NAMESPACE/font/ledger.json"] = """{"providers":[${providers.joinToString(",")}]}""".toByteArray()
    return out
}

/**
 * Animated ledger sprites (helix-gui `ScreenSprite`): the Blockbench-painted frame strips with their `.mcmeta` from
 * `src/pack/resources/anim`, one shifted `item/generated` model and item definition per placement.
 */
private fun sprites(namespace: String, art: Path, sprites: List<de.tytoss.igui.screen.ScreenSprite>): Map<String, ByteArray> {
    val out = linkedMapOf<String, ByteArray>()
    sprites.forEach { sprite ->
        val name = sprite.texture.substringAfterLast('/')
        val texture = "assets/$namespace/textures/item/${sprite.texture}.png"
        out[texture] = Files.readAllBytes(art.resolve("$name.png"))
        out["$texture.mcmeta"] = Files.readAllBytes(art.resolve("$name.png.mcmeta"))
        out["assets/$namespace/models/item/${sprite.model}.json"] = de.tytoss.igui.screen.ScreenSprites.modelJson(namespace, sprite).toByteArray()
        out["assets/$namespace/items/${sprite.model}.json"] = de.tytoss.igui.screen.ScreenSprites.itemJson(namespace, sprite).toByteArray()
    }
    return out
}

/**
 * The network's own sound pack (design §16): every directory under [art] holding OGG files becomes one sound
 * event in the `reich` namespace — `ui/click/1.ogg` → event `reich:ui.click`, sound `reich:ui/click/1`, all
 * files of a directory being random variants. Copies the OGGs to `assets/reich/sounds/` and writes
 * `assets/reich/sounds.json`; every event must be registered in [de.tytoss.igui.sound.NetworkSounds] (and
 * vice versa), so keys and pack can never drift apart. Audio is produced by `tools/sounds/synth.mjs`.
 */
private fun sounds(art: Path): Map<String, ByteArray> {
    val ns = de.tytoss.igui.sound.NetworkSounds
    val out = linkedMapOf<String, ByteArray>()
    val files = Files.walk(art).use { s -> s.filter { it.toString().endsWith(".ogg") }.sorted().toList() }
    val events = files.groupBy { art.relativize(it.parent).toString().replace('\\', '/') }.toSortedMap()
    val keys = events.keys.map { "${ns.NAMESPACE}:${it.replace('/', '.')}" }.toSet()
    require(keys == ns.ALL.toSet()) {
        "Sound pack and NetworkSounds differ: missing files ${ns.ALL - keys}, unregistered ${keys - ns.ALL.toSet()}"
    }
    val json = StringBuilder("{\n")
    events.entries.forEachIndexed { index, (dir, oggs) ->
        val key = "${ns.NAMESPACE}:${dir.replace('/', '.')}"
        val category = ns.category(key)
        val sounds = oggs.joinToString(", ") { file ->
            val name = "$dir/${file.fileName.toString().removeSuffix(".ogg")}"
            out["assets/${ns.NAMESPACE}/sounds/$name.ogg"] = Files.readAllBytes(file)
            val range = if (category == "voice") ""","attenuation_distance":${ns.VOICE_RANGE}""" else ""
            """{"name":"${ns.NAMESPACE}:$name"$range}"""
        }
        val comma = if (index < events.size - 1) "," else ""
        json.append("""  "${dir.replace('/', '.')}": {"category":"$category","sounds":[$sounds]}$comma""").append('\n')
    }
    out["assets/${ns.NAMESPACE}/sounds.json"] = json.append("}\n").toString().toByteArray()
    return out
}

/**
 * The network wordmark (helix-api `Wordmark`): two Blockbench-painted glyphs in `helix_guis:wordmark`, sitting on
 * the text line like a capital letter (large for titles, small for tablist/scoreboard).
 */
private fun wordmark(art: Path): Map<String, ByteArray> {
    val out = linkedMapOf<String, ByteArray>()
    val providers = listOf("wordmark_32" to Pair(0xE000, 20), "wordmark_16" to Pair(0xE001, 13)).map { (name, glyph) ->
        val bytes = Files.readAllBytes(art.resolve("$name.png"))
        out["assets/$NAMESPACE/textures/font/$name.png"] = bytes
        val height = javax.imageio.ImageIO.read(art.resolve("$name.png").toFile()).height
        """{"type":"bitmap","file":"$NAMESPACE:font/$name.png","height":$height,"ascent":${glyph.second},"chars":["\u%04X"]}""".format(glyph.first)
    }
    out["assets/$NAMESPACE/font/wordmark.json"] = """{"providers":[${providers.joinToString(",")}]}""".toByteArray()
    return out
}

/**
 * Inline icons of the shared font `helix_guis:icons` (helix-api `Crown`): 16×16 art shown at half size on the text
 * line (height 8, ascent 7), like the game-mode currency icons. Codepoints must match `Crown.GLYPH`.
 */
private fun icons(art: Path): Map<String, ByteArray> {
    val out = linkedMapOf<String, ByteArray>()
    val providers = listOf("crown" to 0xE000).map { (name, glyph) ->
        out["assets/$NAMESPACE/textures/font/icon_$name.png"] = Files.readAllBytes(art.resolve("$name.png"))
        """{"type":"bitmap","file":"$NAMESPACE:font/icon_$name.png","height":8,"ascent":7,"chars":["\u%04X"]}""".format(glyph)
    }
    out["assets/$NAMESPACE/font/icons.json"] = """{"providers":[${providers.joinToString(",")}]}""".toByteArray()
    return out
}
