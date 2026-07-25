package org.helix.node.addons

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.api.action.ActionInvocation
import org.helix.api.addon.AddonState
import org.helix.node.actions.ActionRegistry

class AddonManagerTest {
    private val directory = createTempDirectory("addons")
    private val registry = ActionRegistry()
    private val manager = AddonManager(directory, registry)

    private fun writeHxa(
        id: String = "helix.test",
        main: String = TestAddon::class.java.name,
        withJar: Boolean = true,
        withPaperComponent: Boolean = false,
        withResourcePack: Boolean = false,
        withVelocityComponent: Boolean = false,
        extraPaperJars: List<String> = emptyList(),
    ): Path {
        val file = directory.resolve("$id.hxa")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("addon.json"))
            zip.write(
                """
                {"id": "$id", "name": "Test", "version": "1.0.0", "main": "$main"}
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            if (withJar) {
                zip.putNextEntry(ZipEntry("addon.jar"))
                val jarBytes = ByteArrayOutputStream().also { buffer ->
                    ZipOutputStream(buffer).use { jar ->
                        jar.putNextEntry(ZipEntry("marker.txt"))
                        jar.write("test".toByteArray())
                        jar.closeEntry()
                    }
                }
                zip.write(jarBytes.toByteArray())
                zip.closeEntry()
            }
            if (withPaperComponent) {
                zip.putNextEntry(ZipEntry("paper.jar"))
                zip.write("paper-plugin".toByteArray())
                zip.closeEntry()
            }
            if (withResourcePack) {
                zip.putNextEntry(ZipEntry("pack.zip"))
                zip.write("pack-bytes".toByteArray())
                zip.closeEntry()
            }
            if (withVelocityComponent) {
                zip.putNextEntry(ZipEntry("velocity.jar"))
                zip.write("velocity-plugin".toByteArray())
                zip.closeEntry()
            }
            extraPaperJars.forEach { name ->
                zip.putNextEntry(ZipEntry("paper/$name.jar"))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `paper component and resource pack are extracted and exposed`() {
        writeHxa(withPaperComponent = true, withResourcePack = true)
        manager.loadAll()

        val components = manager.paperComponents("Lobby")
        assertEquals(listOf("helix.test"), components.map { it.first })
        assertTrue(Files.exists(components.single().second))
        val pack = manager.resourcePack("helix.test")
        assertTrue(pack != null && Files.exists(pack))

        // disabled addons expose neither
        manager.disable("helix.test")
        assertTrue(manager.paperComponents("Lobby").isEmpty())
        assertEquals(null, manager.resourcePack("helix.test"))
    }

    @Test
    fun `velocity components and extra paper jars are extracted`() {
        writeHxa(withPaperComponent = true, withVelocityComponent = true, extraPaperJars = listOf("packetevents"))
        manager.loadAll()

        val paper = manager.paperComponents("Lobby")
        assertEquals(listOf("helix.test", "helix.test-packetevents"), paper.map { it.first }.sorted())
        paper.forEach { (_, path) -> assertTrue(Files.exists(path)) }

        val velocity = manager.velocityComponents("Proxy")
        assertEquals(listOf("helix.test"), velocity.map { it.first })
        assertTrue(Files.exists(velocity.single().second))

        manager.disable("helix.test")
        assertTrue(manager.velocityComponents("Proxy").isEmpty())
    }

    @Test
    fun `addons without extras expose no paper component`() {
        writeHxa()
        manager.loadAll()

        assertTrue(manager.paperComponents("Lobby").isEmpty())
        assertEquals(null, manager.resourcePack("helix.test"))
    }

    @Test
    fun `loadAll installs and enables hxa addons`() {
        writeHxa()

        val addons = manager.loadAll()

        assertEquals(AddonState.ENABLED, addons.single().state)
        val result = registry.invoke(ActionInvocation("test.ping"))
        assertTrue(result.success)
        assertTrue(result.lines.first().contains("helix.test"))
    }

    @Test
    fun `disable removes addon actions and enable restores them`() {
        writeHxa()
        manager.loadAll()

        assertTrue(manager.disable("helix.test"))
        assertFalse(registry.invoke(ActionInvocation("test.ping")).success)
        assertEquals(AddonState.DISABLED, manager.addons().single().state)

        assertTrue(manager.enable("helix.test"))
        assertTrue(registry.invoke(ActionInvocation("test.ping")).success)
    }

    @Test
    fun `broken main class ends in FAILED state`() {
        writeHxa(id = "helix.broken", main = "does.not.Exist")

        manager.loadAll()

        assertEquals(AddonState.FAILED, manager.addons().single().state)
    }

    @Test
    fun `missing addon jar is reported and skipped`() {
        writeHxa(id = "helix.nojar", withJar = false)

        assertTrue(manager.loadAll().isEmpty())
    }

    @Test
    fun `reload loads new addons and leaves existing ones untouched`() {
        writeHxa(id = "helix.first")
        manager.loadAll()
        val firstAction = registry.invoke(ActionInvocation("test.ping"))
        assertTrue(firstAction.success)

        // a second addon appears in the folder at runtime
        writeHxa(id = "helix.second", main = SecondTestAddon::class.java.name)
        val added = manager.reload()

        assertEquals(listOf("helix.second"), added.map { it.manifest.id })
        assertEquals(
            setOf("helix.first", "helix.second"),
            manager.addons().map { it.manifest.id }.toSet(),
        )
        // reloading again finds nothing new
        assertTrue(manager.reload().isEmpty())
    }

    @Test
    fun `reload skips broken packages`() {
        directory.resolve("broken.hxa").writeText("not a zip")

        assertTrue(manager.reload().isEmpty())
    }

    @Test
    fun `disable all runs on shutdown`() {
        writeHxa()
        manager.loadAll()

        manager.disableAll()

        assertEquals(AddonState.DISABLED, manager.addons().single().state)
    }
}
