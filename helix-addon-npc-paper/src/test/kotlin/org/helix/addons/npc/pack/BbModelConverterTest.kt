package org.helix.addons.npc.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addons.npc.paper.villager.VillagerCast
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BbModelConverterTest {
    private val png = byteArrayOf(1, 2, 3)
    private val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)

    private fun cube(uuid: String, from: String, to: String, extra: String = "") = """
        {"uuid":"$uuid","name":"$uuid","type":"cube","from":$from,"to":$to,"origin":[8,0,8]$extra,
         "faces":{"north":{"uv":[0,0,32,64],"texture":0},"south":{"uv":[0,0,4,4],"texture":null}}}
    """

    /** Blockbench 5 layout: groups in a top-level array, outliner holds uuid trees. */
    private val project = """
        {"meta":{"format_version":"5.0","model_format":"java_block"},
         "resolution":{"width":128,"height":128},
         "elements":[
           ${cube("shoe", "[6,0,6]", "[10,2,10]")},
           ${cube("robe", "[3.5,8,5]", "[12.5,20,11]")},
           ${cube("skull", "[2,20,2.5]", "[14,32,13.5]")},
           ${cube("hat", "[1,32,1]", "[15,60,15]", ",\"rotation\":[0,0,22.5]")},
           ${cube("lid", "[8.8,25.55,1.9]", "[12.2,28.15,2.2]")}
         ],
         "groups":[
           {"uuid":"g-root","name":"root","origin":[8,0,8],"rotation":[0,0,0]},
           {"uuid":"g-body","name":"body","origin":[8,0,8],"rotation":[0,0,0]},
           {"uuid":"g-head","name":"head","origin":[8,20,8],"rotation":[0,0,0]},
           {"uuid":"g-lid","name":"lid_l","origin":[10.5,28.15,2.05],"rotation":[0,0,0]}
         ],
         "outliner":[{"uuid":"g-root","children":["shoe",{"uuid":"g-body","children":["robe",
           {"uuid":"g-head","children":["skull","hat",{"uuid":"g-lid","children":["lid"]}]}]}]}],
         "textures":[{"uv_width":128,"uv_height":128,"source":"$dataUrl"}]}
    """

    private fun elements(model: String) = Json.parseToJsonElement(model).jsonObject["elements"]!!.jsonArray
    private fun nums(any: Any) = (any as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.double }

    @Test
    fun `builds the part hierarchy with pivots from the groups array`() {
        val v = BbModelConverter.convert("t", project)
        assertEquals(listOf("root", "body", "head", "lid_l"), v.rig.parts.map { it.name })
        assertEquals(listOf(null, "root", "body", "head"), v.rig.parts.map { it.parent })
        assertEquals(listOf(10.5, 28.15, 2.05), v.rig.parts.last().pivot)
        assertTrue(png.contentEquals(v.texture))
        assertEquals(60.0, v.rig.height)
    }

    @Test
    fun `shifts each part so its pivot sits at 8 8 8`() {
        val v = BbModelConverter.convert("t", project)
        val lid = elements(v.models.getValue("lid_l")).single().jsonObject
        assertEquals(listOf(6.3, 5.4, 7.85), nums(lid["from"]!!))
        assertEquals(listOf(9.7, 8.0, 8.15), nums(lid["to"]!!))
        val shoe = elements(v.models.getValue("root")).single().jsonObject
        assertEquals(listOf(6.0, 8.0, 6.0), nums(shoe["from"]!!))
    }

    @Test
    fun `scales uvs from texture pixels to the 0-16 range and drops untextured faces`() {
        val v = BbModelConverter.convert("t", project)
        val faces = elements(v.models.getValue("body")).single().jsonObject["faces"]!!.jsonObject
        assertEquals(listOf(0.0, 0.0, 4.0, 8.0), nums(faces["north"]!!.jsonObject["uv"]!!))
        assertEquals("#0", faces["north"]!!.jsonObject["texture"]!!.jsonPrimitive.content)
        assertFalse("south" in faces)
    }

    @Test
    fun `shrinks parts that leave the java bounds and records the factor`() {
        val v = BbModelConverter.convert("t", project)
        val head = v.rig.parts.first { it.name == "head" }
        // hat top is 40 px above the neck pivot: 40 / 24
        assertEquals(1.6667, head.scale)
        elements(v.models.getValue("head")).forEach { e ->
            (nums(e.jsonObject["from"]!!) + nums(e.jsonObject["to"]!!)).forEach { assertTrue(it in -16.0..32.0, "$it") }
        }
        val hat = elements(v.models.getValue("head")).first { it.jsonObject["name"]!!.jsonPrimitive.content == "hat" }.jsonObject
        val rotation = hat["rotation"]!!.jsonObject
        assertEquals("z", rotation["axis"]!!.jsonPrimitive.content)
        assertEquals(22.5, rotation["angle"]!!.jsonPrimitive.double)
        // rotation origin (8,0,8) is shifted and shrunk like the geometry: 8 + (0 - 20) / 1.6667
        assertEquals(-4.0, nums(rotation["origin"]!!)[1], 0.01)
        assertEquals(1.0, v.rig.parts.first { it.name == "body" }.scale)
    }

    @Test
    fun `converts every committed villager model with the contract parts`() {
        val dir = File("src/pack/resources/villagers")
        val files = dir.listFiles { f -> f.extension == "bbmodel" }.orEmpty()
        files.forEach { file ->
            val v = BbModelConverter.convert(file.nameWithoutExtension, file.readText())
            val names = v.rig.parts.map { it.name }
            listOf("root", "body", "head", "brow", "lid_l", "lid_r", "mouth", "nose").forEach {
                assertTrue(it in names, "${file.name} lacks part $it")
            }
            assertTrue(file.nameWithoutExtension in VillagerCast.IDS, "${file.name} is not in the cast")
            v.models.values.flatMap { elements(it) }.forEach { e ->
                (nums(e.jsonObject["from"]!!) + nums(e.jsonObject["to"]!!)).forEach { assertTrue(it in -16.0..32.0) }
            }
        }
    }
}
