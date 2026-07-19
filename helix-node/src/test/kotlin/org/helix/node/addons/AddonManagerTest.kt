package org.helix.node.addons

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionResult
import org.helix.api.addon.AddonContext
import org.helix.api.addon.AddonState
import org.helix.api.addon.HelixAddon
import org.helix.node.actions.ActionRegistry

/**
 * Test addon resolved through the parent classloader.
 */
class TestAddon : HelixAddon {
    override fun onEnable(context: AddonContext) {
        context.registerAction(ActionDescriptor("test.ping", "ping", "test.ping")) {
            ActionResult.ok("pong from ${context.dataDirectory.fileName}")
        }
    }
}

class AddonManagerTest {
    private val directory = createTempDirectory("addons")
    private val registry = ActionRegistry()
    private val manager = AddonManager(directory, registry)

    private fun writeHxa(
        id: String = "helix.test",
        main: String = TestAddon::class.java.name,
        withJar: Boolean = true,
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
        }
        return file
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
    fun `disable all runs on shutdown`() {
        writeHxa()
        manager.loadAll()

        manager.disableAll()

        assertEquals(AddonState.DISABLED, manager.addons().single().state)
    }
}
