package org.helix.api.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchemaMigratorTest {
    private val json = Json

    @Test
    fun `a fresh key reads as null`() {
        val migrator = SchemaMigrator(currentVersion = 1)
        assertNull(migrator.read(InMemoryAddonStorage(), "doc"))
    }

    @Test
    fun `write wraps the body at the current version and read returns it unchanged`() {
        val storage = InMemoryAddonStorage()
        val migrator = SchemaMigrator(currentVersion = 2)

        migrator.write(storage, "doc", JsonPrimitive("hello"))
        val body = migrator.read(storage, "doc")

        assertEquals("hello", body?.jsonPrimitive?.content)
        // already current: no rewrite, envelope on disk still says version 2
        val envelope = json.decodeFromString(VersionedDocument.serializer(), storage.read("doc")!!)
        assertEquals(2, envelope.schemaVersion)
    }

    @Test
    fun `a legacy un-enveloped document is treated as version 0 and migrated on read`() {
        val storage = InMemoryAddonStorage()
        storage.write("doc", """["a","b"]""")

        val migrator = SchemaMigrator(
            currentVersion = 1,
            migrations = mapOf(0 to { body -> body }),
        )
        val body = migrator.read(storage, "doc")

        assertEquals("""["a","b"]""", body.toString())
        // the migration ran and was persisted back in enveloped form
        val envelope = json.decodeFromString(VersionedDocument.serializer(), storage.read("doc")!!)
        assertEquals(1, envelope.schemaVersion)
        assertEquals("""["a","b"]""", envelope.data.toString())
    }

    @Test
    fun `migrations chain across multiple versions`() {
        val storage = InMemoryAddonStorage()
        storage.write("doc", "1") // legacy: a bare number, version 0

        val migrator = SchemaMigrator(
            currentVersion = 2,
            migrations = mapOf(
                0 to { body -> JsonPrimitive(body.jsonPrimitive.content.toInt() + 10) },
                1 to { body -> JsonPrimitive(body.jsonPrimitive.content.toInt() + 100) },
            ),
        )

        val body = migrator.read(storage, "doc")

        assertEquals("111", body?.jsonPrimitive?.content)
    }

    @Test
    fun `a migration missing for the persisted version stops the chain without persisting`() {
        val storage = InMemoryAddonStorage()
        storage.write("doc", "1") // legacy: version 0, but no migration registered for it

        val migrator = SchemaMigrator(currentVersion = 1, migrations = emptyMap())
        val body = migrator.read(storage, "doc")

        // the raw legacy body is still returned...
        assertEquals("1", body?.jsonPrimitive?.content)
        // ...but nothing was rewritten, since no migration could run
        assertEquals("1", storage.read("doc"))
    }
}
