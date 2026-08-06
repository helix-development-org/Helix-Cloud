package org.helix.node.packs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkPackServiceTest {
    private val directory = createTempDirectory("helix-packs")
    private val service = NetworkPackService(directory.resolve("out"))

    private fun fakePack(name: String, entries: Map<String, String>): Path {
        val file = directory.resolve(name)
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            entries.forEach { (entryName, content) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun entriesOf(pack: Path): Map<String, String> = ZipFile(pack.toFile()).use { zip ->
        zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).readAllBytes().decodeToString()
        }
    }

    @Test
    fun `merges distinct entries and generates the mcmeta`() {
        val a = fakePack("a.zip", mapOf("pack.mcmeta" to """{"a":1}""", "assets/a/x.png" to "A"))
        val b = fakePack("b.zip", mapOf("pack.mcmeta" to """{"b":1}""", "assets/b/y.png" to "B"))

        service.rebuild(listOf("helix.a" to a, "helix.b" to b))

        val merged = entriesOf(service.packFile()!!)
        assertEquals("A", merged["assets/a/x.png"])
        assertEquals("B", merged["assets/b/y.png"])
        // the addons' own pack.mcmeta entries are replaced by a generated one
        val meta = Json.parseToJsonElement(merged["pack.mcmeta"]!!).jsonObject["pack"]!!.jsonObject
        assertEquals(46, meta["pack_format"]!!.jsonPrimitive.int)
        assertEquals(34, meta["supported_formats"]!!.jsonArray[0].jsonPrimitive.int)
        assertEquals("Helix network pack", meta["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `conflicting entry keeps the first addon sorted by id`() {
        // registered out of order — the merge sorts by addon id, so helix.a wins
        val b = fakePack("b.zip", mapOf("assets/shared.png" to "FROM-B", "assets/only-b.png" to "B"))
        val a = fakePack("a.zip", mapOf("assets/shared.png" to "FROM-A", "assets/only-a.png" to "A"))

        service.rebuild(listOf("helix.b" to b, "helix.a" to a))

        val merged = entriesOf(service.packFile()!!)
        assertEquals("FROM-A", merged["assets/shared.png"])
        assertEquals("A", merged["assets/only-a.png"])
        assertEquals("B", merged["assets/only-b.png"])
    }

    @Test
    fun `sha1 is stable across rebuilds with the same input`() {
        val a = fakePack("a.zip", mapOf("assets/a.png" to "A"))
        val b = fakePack("b.zip", mapOf("assets/b.png" to "B"))

        service.rebuild(listOf("helix.a" to a, "helix.b" to b))
        val first = service.sha1()
        service.rebuild(listOf("helix.a" to a, "helix.b" to b))

        assertEquals(40, first!!.length)
        assertEquals(first, service.sha1())
    }

    @Test
    fun `no packs yields no file and no sha1`() {
        val a = fakePack("a.zip", mapOf("assets/a.png" to "A"))
        service.rebuild(listOf("helix.a" to a))

        service.rebuild(emptyList())

        assertNull(service.packFile())
        assertNull(service.sha1())
    }

    @Test
    fun `public url override is persisted and clearable`() {
        assertNull(service.publicUrl())

        service.setPublicUrl("http://mc.example.com:8080")
        assertEquals("http://mc.example.com:8080", service.publicUrl())

        service.setPublicUrl(null)
        assertNull(service.publicUrl())
    }
}
