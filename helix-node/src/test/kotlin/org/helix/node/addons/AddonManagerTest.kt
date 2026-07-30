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
    private val panels = org.helix.node.dashboard.DashboardPanelRegistry()
    private val manager = AddonManager(directory, registry, dashboardPanels = panels)

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
    fun `an addon without an own panel gets a generated default page`() {
        writeHxa(withPaperComponent = true)
        manager.loadAll()

        val panel = panels.find("addon-helix-test")
        assertTrue(panel != null, "default panel missing")
        assertTrue(panel.html.contains("test.ping"))
        assertTrue(panel.html.contains("paper.jar"))

        manager.disable("helix.test")
        assertTrue(panels.find("addon-helix-test") == null, "default panel must disappear on disable")

        manager.enable("helix.test")
        assertTrue(panels.find("addon-helix-test") != null, "default panel must return on re-enable")
    }

    @Test
    fun `an addon registering its own panel suppresses the default page`() {
        writeHxa(main = PanelTestAddon::class.java.name)
        manager.loadAll()

        assertTrue(panels.find("custom") != null, "own panel missing")
        assertTrue(panels.find("addon-helix-test") == null, "default page must not exist alongside an own panel")
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

        val info = manager.addons().single()
        assertEquals(AddonState.FAILED, info.state)
        assertTrue(info.failureReason?.isNotBlank() == true)
    }

    @Test
    fun `onEnable throwing ends in FAILED state with a reason and can be retried`() {
        writeHxa(id = "helix.throws", main = ThrowingTestAddon::class.java.name)

        manager.loadAll()
        val info = manager.addons().single()
        assertEquals(AddonState.FAILED, info.state)
        assertTrue(info.failureReason?.contains("boom") == true)

        // retrying enable() must not fail differently (eg. because of a leaked classloader or
        // stale instance reference from the previous failed attempt) — it fails the same way.
        assertFalse(manager.enable("helix.throws"))
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
    fun `malicious paper entry names cannot escape the extracted directory`() {
        // Resolves (pre-fix) to exactly `directory.parent/helix-evil-marker.jar`:
        // one `..` cancels the fake "id-version-paper-.." segment, the other three
        // walk up .extracted -> directory -> directory.parent.
        val outsideMarker = directory.parent.resolve("helix-evil-marker.jar")
        writeHxa(withPaperComponent = true, extraPaperJars = listOf("../../../../helix-evil-marker"))

        manager.loadAll()

        // the legitimate paper.jar still loads; the traversal attempt is skipped, not honored
        val paper = manager.paperComponents("Lobby")
        assertEquals(listOf("helix.test"), paper.map { it.first })
        val extractedRoot = directory.resolve(".extracted").normalize()
        paper.forEach { (_, path) -> assertTrue(path.normalize().startsWith(extractedRoot)) }
        assertFalse(Files.exists(outsideMarker))
    }

    @Test
    fun `manifest with an unsafe id or version is rejected`() {
        val file = directory.resolve("evil.hxa")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("addon.json"))
            zip.write(
                """{"id": "../../evil", "name": "Evil", "version": "1.0.0", "main": "does.not.Exist"}""".toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("addon.jar"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }

        assertTrue(manager.loadAll().isEmpty())
    }

    @Test
    fun `disable all runs on shutdown`() {
        writeHxa()
        manager.loadAll()

        manager.disableAll()

        assertEquals(AddonState.DISABLED, manager.addons().single().state)
    }
}
