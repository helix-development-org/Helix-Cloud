package org.helix.addons.npc.pack

import kotlinx.serialization.json.Json
import org.helix.addons.npc.paper.villager.RigBundle
import org.helix.addons.npc.paper.villager.VillagerCast
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Resource pack of the villager NPCs: reads every `<id>.bbmodel` in the villager directory and writes, in namespace
 * `helix_villager`, the texture (`textures/item/<id>.png`), one item model per part (`models/item/<id>/<part>.json`)
 * and its item definition (`items/<id>/<part>.json`). The rig description of all villagers goes to a separate JSON
 * file the plugin jar bundles ([RigBundle.RESOURCE]). Missing models are skipped with a warning, so the build works
 * with any number of them.
 *
 * Models carry no `display` block: the plugin renders them with item transform `NONE`, which applies none.
 *
 * @param args `[0]` pack zip path, `[1]` rig JSON path, `[2]` the villager model directory.
 */
fun main(args: Array<String>) {
    val output = Path.of(args[0])
    val rigOutput = Path.of(args[1])
    val dir = Path.of(args[2])
    val ns = RigBundle.NAMESPACE
    val files = if (dir.exists()) dir.listDirectoryEntries().filter { it.extension == "bbmodel" }.sortedBy { it.fileName } else emptyList()
    val found = files.map { it.nameWithoutExtension }.toSet()
    VillagerCast.IDS.filter { it !in found }.forEach { println("WARNING: villager model '$it.bbmodel' missing — skipped") }

    val entries = linkedMapOf<String, ByteArray>()
    entries["pack.mcmeta"] = """{"pack":{"pack_format":46,"supported_formats":{"min_inclusive":46,"max_inclusive":999}, "min_format": 46, "max_format": 999,"description":"Villagers"}}""".toByteArray()
    val rigs = linkedMapOf<String, org.helix.addons.npc.paper.villager.RigSpec>()
    files.forEach { file ->
        val id = BbModelConverter.sanitize(file.nameWithoutExtension)
        val villager = BbModelConverter.convert(id, file.readText())
        villager.warnings.forEach { println("WARNING: $it") }
        entries["assets/$ns/textures/item/$id.png"] = villager.texture
        villager.models.forEach { (part, model) ->
            entries["assets/$ns/models/item/$id/$part.json"] = model.toByteArray()
            entries["assets/$ns/items/$id/$part.json"] =
                """{"model":{"type":"minecraft:model","model":"$ns:item/$id/$part"}}""".toByteArray()
        }
        rigs[id] = villager.rig
    }

    Files.createDirectories(output.parent)
    ZipOutputStream(Files.newOutputStream(output)).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name).apply { time = 0L })
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    Files.createDirectories(rigOutput.parent)
    Files.writeString(rigOutput, Json.encodeToString(RigBundle(rigs)))
    println("Villager pack written to $output (${rigs.size} villager(s))")
}
