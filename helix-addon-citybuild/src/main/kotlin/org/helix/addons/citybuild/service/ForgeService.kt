package org.helix.addons.citybuild.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.helix.addons.citybuild.config.ConfigDocument
import org.helix.addons.citybuild.model.SerialRecord

/**
 * Admin forge templates and serial numbers.
 *
 * Templates live in the config collection `forge` (document id = template id, body
 * `{name, version, updatedBy, updatedAt, spec}`), so they are part of the config bundle and every
 * instance picks up changes with the 15 s version poll (live update of issued items). The `spec` is
 * the instance's item model and opaque to the node. Every handed-out item gets a serial number.
 *
 * @property ctx shared dependencies.
 */
class ForgeService(private val ctx: CityContext) {
    /**
     * Creates or updates a template; the version increases by one on every save.
     *
     * @param id template id (`[a-z0-9_]{1,48}`).
     * @param name display name.
     * @param updatedBy acting player uuid.
     * @param spec item model of the instance.
     * @return the stored body.
     */
    @Synchronized
    fun save(id: String, name: String, updatedBy: String, spec: JsonElement): JsonObject {
        if (!ID.matches(id) || id == ONE_OFF) Results.fail("invalid_id", "template id must match ${ID.pattern}")
        val previous = ctx.config.document(COLLECTION, id)
        val version = (previous?.body?.get("version")?.jsonPrimitive?.intOrNull ?: 0) + 1
        val body = jsonOf("name" to name, "version" to version, "updatedBy" to updatedBy, "updatedAt" to ctx.now(), "spec" to spec)
        ctx.config.putDocument(COLLECTION, ConfigDocument(id, previous?.sortOrder ?: ctx.config.documents(COLLECTION).size, body))
        ctx.log("forge.save", "", updatedBy, ref = "forge:$id", data = jsonOf("version" to version))
        return body.with("id" to id)
    }

    /**
     * Issues serial numbers for handed-out items.
     *
     * @param uuid receiving player.
     * @param shard instance.
     * @param forgeId template id, or `_oneoff` for an item made without template.
     * @param source channel (`give`, `kit`, `crate`, `offer`, `reward`, `forge`).
     * @param count number of items (1–64), each gets its own serial.
     * @return `{forgeId, version, serials, spec}`.
     */
    fun issue(uuid: String, shard: String, forgeId: String, source: String, count: Int): JsonObject {
        val template = if (forgeId == ONE_OFF) null else ctx.config.document(COLLECTION, forgeId) ?: Results.fail("not_found", "no template '$forgeId'")
        val version = template?.body?.get("version")?.jsonPrimitive?.intOrNull ?: 0
        val serials = (1..count.coerceIn(1, 64)).map {
            ctx.repository.issueSerial(SerialRecord(0, forgeId, version, uuid, source, shard, ctx.now())).serial
        }
        ctx.log("forge.issue", shard, uuid, ref = "forge:$forgeId", data = jsonOf("serials" to serials, "source" to source))
        return jsonOf("forgeId" to forgeId, "version" to version, "serials" to serials, "spec" to template?.body?.get("spec"))
    }

    /**
     * Registers the forge actions.
     *
     * @param r the registrar.
     */
    fun register(r: ActionRegistrar) {
        r.register("citybuild.forge.list", "citybuild.forge.list", "All templates [{id, name, version, updatedBy, updatedAt, spec}].", null) { _ ->
            Results.ok(JsonArray(ctx.config.documents(COLLECTION).map { it.body.with("id" to it.id) }))
        }
        r.register("citybuild.forge.get", "citybuild.forge.get <id>", "One template.", null) { a ->
            val doc = ctx.config.document(COLLECTION, a.str(0, "id")) ?: Results.fail("not_found", "no such template")
            Results.ok(doc.body.with("id" to doc.id))
        }
        r.register("citybuild.forge.save", "citybuild.forge.save <uuid> <id> <json {name, spec}>", "Creates or updates a template.", "citybuild.admin.forge") { a ->
            val body = a.json(2)
            val spec = body["spec"] ?: Results.fail("invalid", "spec missing")
            Results.ok(save(a.str(1, "id"), body.string("name") ?: a.str(1, "id"), a.str(0, "uuid"), spec))
        }
        r.register("citybuild.forge.delete", "citybuild.forge.delete <uuid> <id>", "Deletes a template (issued items stay frozen).", "citybuild.admin.forge") { a ->
            if (!ctx.config.removeDocument(COLLECTION, a.str(1, "id"))) Results.fail("not_found", "no such template")
            ctx.log("forge.delete", "", a.str(0, "uuid"), ref = "forge:${a.str(1, "id")}")
            Results.ok(jsonOf("deleted" to true))
        }
        r.register("citybuild.forge.issue", "citybuild.forge.issue <uuid> <shard> <forgeId|_oneoff> <source> [count]", "Issues serials for handed-out items.", null) { a ->
            Results.ok(issue(a.str(0, "uuid"), a.str(1, "shard"), a.str(2, "forgeId"), a.str(3, "source"), a.intOr(4, 1)))
        }
        r.register("citybuild.forge.track", "citybuild.forge.track <serial>", "Origin of a serial number.", "citybuild.admin.logs") { a ->
            val record = ctx.repository.serial(a.long(0, "serial")) ?: Results.fail("not_found", "unknown serial")
            Results.ok(CityContext.json.encodeToJsonElement(record))
        }
    }

    /** Collection and id conventions. */
    companion object {
        /** Config collection of the templates. */
        const val COLLECTION: String = "forge"

        /** Pseudo template id of items forged without template. */
        const val ONE_OFF: String = "_oneoff"

        private val ID = Regex("[a-z0-9_]{1,48}")
    }
}
