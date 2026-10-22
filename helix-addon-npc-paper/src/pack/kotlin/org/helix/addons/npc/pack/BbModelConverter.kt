package org.helix.addons.npc.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.helix.addons.npc.paper.villager.RigBundle
import org.helix.addons.npc.paper.villager.RigPart
import org.helix.addons.npc.paper.villager.RigSpec
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * A villager converted from its Blockbench project.
 *
 * @property id villager id.
 * @property texture PNG bytes of the embedded texture.
 * @property models Java item model JSON per part name (parts without elements have none).
 * @property rig the rig description for the plugin.
 * @property warnings non-fatal problems found while converting.
 */
class ConvertedVillager(
    val id: String,
    val texture: ByteArray,
    val models: Map<String, String>,
    val rig: RigSpec,
    val warnings: List<String>,
)

/**
 * Converts a Blockbench 4/5 `java_block` project into one Java item model per outliner group ("part").
 *
 * A part's elements are the cubes directly inside its group. Every part model is shifted so the group origin (the
 * pivot) sits at (8, 8, 8) — the point an item display renders at its origin with transform `NONE`. Parts that would
 * leave the Java element bounds (-16..32) are shrunk around the pivot; the factor is recorded as [RigPart.scale] and
 * undone by the display scale. Face UVs are converted from texture pixels to the 0..16 model range.
 */
object BbModelConverter {
    private val json = Json { ignoreUnknownKeys = true }
    private val faceNames = listOf("north", "east", "south", "west", "up", "down")
    private val validAngles = setOf(-45.0, -22.5, 0.0, 22.5, 45.0)

    /** Largest distance from the centre (8) a Java element coordinate may have. */
    const val MAX_EXTENT = 24.0

    /**
     * @param id villager id (namespace path of texture and models).
     * @param source the `.bbmodel` JSON.
     * @return the converted villager.
     */
    fun convert(id: String, source: String): ConvertedVillager {
        val root = json.parseToJsonElement(source).jsonObject
        val warnings = mutableListOf<String>()
        val elements = root["elements"]?.jsonArray.orEmpty().map { it.jsonObject }
            .associateBy { it.str("uuid") ?: "" }
        val groups = root["groups"]?.jsonArray.orEmpty().map { it.jsonObject }.associateBy { it.str("uuid") ?: "" }
        val texture = root["textures"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("$id: no texture")
        val resolution = root["resolution"]?.jsonObject
        val uvWidth = texture.num("uv_width") ?: resolution?.num("width") ?: 16.0
        val uvHeight = texture.num("uv_height") ?: resolution?.num("height") ?: 16.0
        val png = decodeDataUrl(texture.str("source") ?: error("$id: texture has no embedded source"))

        // walk the outliner: parts in parent-before-child order with their direct elements
        class Part(val name: String, val parent: String?, val pivot: List<Double>, val rest: List<Double>) {
            val cubes = mutableListOf<JsonObject>()
        }
        val parts = mutableListOf<Part>()
        val loose = mutableListOf<JsonObject>()
        fun walk(node: JsonElement, parent: Part?) {
            if (node is JsonPrimitive) {
                val element = elements[node.content]
                when {
                    element == null -> warnings += "$id: outliner references unknown element ${node.content}"
                    element["export"]?.jsonPrimitive?.booleanOrNull == false -> Unit
                    element.str("type").let { it != null && it != "cube" } ->
                        warnings += "$id: element '${element.str("name")}' is not a cube — skipped"
                    parent == null -> loose += element
                    else -> parent.cubes += element
                }
                return
            }
            val obj = node.jsonObject
            val group = groups[obj.str("uuid")] ?: obj
            if (group["export"]?.jsonPrimitive?.booleanOrNull == false) return
            var name = sanitize(group.str("name") ?: "part")
            if (parts.any { it.name == name }) {
                warnings += "$id: duplicate group name '$name' — renamed"
                var n = 2
                while (parts.any { it.name == "${name}_$n" }) n++
                name = "${name}_$n"
            }
            val part = Part(name, parent?.name, group.vec("origin") ?: listOf(0.0, 0.0, 0.0), group.vec("rotation") ?: listOf(0.0, 0.0, 0.0))
            parts += part
            obj["children"]?.jsonArray.orEmpty().forEach { walk(it, part) }
        }
        root["outliner"]?.jsonArray.orEmpty().forEach { walk(it, null) }
        if (loose.isNotEmpty()) {
            val target = parts.firstOrNull { it.name == "root" } ?: parts.firstOrNull()
            warnings += "$id: ${loose.size} element(s) outside any group — added to '${target?.name ?: "nothing"}'"
            target?.cubes?.addAll(loose)
        }

        val models = LinkedHashMap<String, String>()
        val rigParts = parts.map { part ->
            val pivot = part.pivot
            var extent = 0.0
            part.cubes.forEach { cube ->
                val inflate = cube.num("inflate") ?: 0.0
                val from = cube.vec("from") ?: listOf(0.0, 0.0, 0.0)
                val to = cube.vec("to") ?: listOf(0.0, 0.0, 0.0)
                for (axis in 0..2) {
                    extent = max(extent, abs(from[axis] - inflate - pivot[axis]))
                    extent = max(extent, abs(to[axis] + inflate - pivot[axis]))
                }
            }
            val scale = if (extent > MAX_EXTENT) extent / MAX_EXTENT else 1.0
            if (scale > 1.0) warnings += "$id: part '${part.name}' exceeds the Java bounds — shrunk by ${round4(scale)}"
            if (part.cubes.isNotEmpty()) {
                models[part.name] = partModel(id, part.cubes, pivot, scale, uvWidth, uvHeight) { warnings += "$id/${part.name}: $it" }
            }
            RigPart(part.name, part.parent, pivot, part.rest, part.cubes.isNotEmpty(), round4(scale))
        }
        val height = elements.values.maxOfOrNull { (it.vec("to")?.get(1) ?: 0.0) + (it.num("inflate") ?: 0.0) } ?: 32.0
        return ConvertedVillager(id, png, models, RigSpec(rigParts, round4(height)), warnings)
    }

    /**
     * Builds the Java model of one part.
     *
     * @param id villager id (texture reference).
     * @param cubes the part's elements.
     * @param pivot the part pivot in model pixels.
     * @param scale shrink factor around the pivot (1 = none).
     * @param uvWidth texture width the face UVs refer to.
     * @param uvHeight texture height the face UVs refer to.
     * @param warn receives conversion warnings.
     * @return the model JSON.
     */
    fun partModel(
        id: String,
        cubes: List<JsonObject>,
        pivot: List<Double>,
        scale: Double,
        uvWidth: Double,
        uvHeight: Double,
        warn: (String) -> Unit = {},
    ): String {
        fun place(c: List<Double>) = buildJsonArray {
            for (axis in 0..2) add(JsonPrimitive(round4(8.0 + (c[axis] - pivot[axis]) / scale)))
        }
        val texture = "${RigBundle.NAMESPACE}:item/$id"
        val model = buildJsonObject {
            putJsonObject("textures") {
                put("0", texture)
                put("particle", texture)
            }
            putJsonArray("elements") {
                cubes.forEach { cube ->
                    val name = cube.str("name") ?: "cube"
                    val inflate = cube.num("inflate") ?: 0.0
                    val from = (cube.vec("from") ?: listOf(0.0, 0.0, 0.0)).map { it - inflate }
                    val to = (cube.vec("to") ?: listOf(0.0, 0.0, 0.0)).map { it + inflate }
                    add(buildJsonObject {
                        put("name", name)
                        put("from", place(from))
                        put("to", place(to))
                        val rotation = cube.vec("rotation") ?: listOf(0.0, 0.0, 0.0)
                        val axes = (0..2).filter { rotation[it] != 0.0 }
                        if (axes.isNotEmpty()) {
                            if (axes.size > 1) warn("'$name' rotates on several axes — only the largest is kept")
                            val axis = axes.maxBy { abs(rotation[it]) }
                            val angle = rotation[axis]
                            if (angle !in validAngles) warn("'$name' angle $angle is not a vanilla step (±22.5/±45)")
                            putJsonObject("rotation") {
                                put("angle", angle)
                                put("axis", "xyz"[axis].toString())
                                put("origin", place(cube.vec("origin") ?: listOf(8.0, 8.0, 8.0)))
                                if (cube["rescale"]?.jsonPrimitive?.booleanOrNull == true) put("rescale", true)
                            }
                        }
                        if (cube["shade"]?.jsonPrimitive?.booleanOrNull == false) put("shade", false)
                        val light = cube["light_emission"]?.jsonPrimitive?.intOrNull ?: 0
                        if (light > 0) put("light_emission", light)
                        putJsonObject("faces") {
                            val faces = cube["faces"]?.jsonObject
                            faceNames.forEach { face ->
                                val f = faces?.get(face) as? JsonObject ?: return@forEach
                                val tex = f["texture"]
                                if (tex == null || tex is kotlinx.serialization.json.JsonNull) return@forEach
                                if (tex.jsonPrimitive.intOrNull.let { it != null && it != 0 }) {
                                    warn("'$name' $face uses another texture than the first — mapped to it anyway")
                                }
                                val uv = f.vec("uv") ?: return@forEach
                                putJsonObject(face) {
                                    put("uv", buildJsonArray {
                                        add(JsonPrimitive(round4(uv[0] * 16.0 / uvWidth)))
                                        add(JsonPrimitive(round4(uv[1] * 16.0 / uvHeight)))
                                        add(JsonPrimitive(round4(uv[2] * 16.0 / uvWidth)))
                                        add(JsonPrimitive(round4(uv[3] * 16.0 / uvHeight)))
                                    })
                                    val rot = f["rotation"]?.jsonPrimitive?.intOrNull ?: 0
                                    if (rot != 0) put("rotation", rot)
                                    put("texture", "#0")
                                }
                            }
                        }
                    })
                }
            }
        }
        return model.toString()
    }

    /**
     * @param url a `data:image/png;base64,…` URL.
     * @return the decoded bytes.
     */
    fun decodeDataUrl(url: String): ByteArray {
        val comma = url.indexOf(',')
        require(url.startsWith("data:") && comma > 0) { "not a data URL" }
        return Base64.getDecoder().decode(url.substring(comma + 1))
    }

    /**
     * @param name a Blockbench name.
     * @return a resource-path-safe lowercase name.
     */
    fun sanitize(name: String): String =
        name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' || it == '.') it else '_' }
            .joinToString("").ifEmpty { "part" }

    private fun round4(v: Double): Double = round(v * 10000.0) / 10000.0

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.vec(key: String): List<Double>? =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }
}
