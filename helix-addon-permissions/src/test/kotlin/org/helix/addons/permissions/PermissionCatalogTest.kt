package org.helix.addons.permissions

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionHandler
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import org.helix.api.addon.AddonContext
import org.helix.api.addon.AddonInfo
import org.helix.api.addon.AddonManifest
import org.helix.api.addon.AddonState
import org.helix.api.addon.JoinGate
import org.helix.api.addon.PermissionResolver

/**
 * Fake context feeding the catalog with addons, core nodes and a workspace.
 */
private class CatalogContext(
    override val dataDirectory: Path,
    private val workspace: Path,
) : AddonContext {
    override val actions: ActionInvoker = object : ActionInvoker {
        override fun invoke(invocation: ActionInvocation): ActionResult = ActionResult.ok()

        override fun descriptors() = emptyList<ActionDescriptor>()
    }

    override fun registerAction(descriptor: ActionDescriptor, handler: ActionHandler) {
    }

    override fun registerJoinGate(gate: JoinGate) {
    }

    override fun registerPermissionResolver(resolver: PermissionResolver) {
    }

    override fun installedAddons(): List<AddonInfo> = listOf(
        AddonInfo(
            AddonManifest(
                id = "helix.bans",
                name = "Bans",
                version = "1.0.0",
                main = "x",
                permissions = listOf("helix.bans"),
            ),
            AddonState.ENABLED,
        ),
    )

    override fun corePermissions(): List<String> = listOf("helix.panel.login", "helix.bans")

    override fun serviceDirectories(): List<Path> = listOf(workspace)
}

class PermissionCatalogTest {
    @Test
    fun `catalog merges addon, core and plugin permissions`() {
        val workspace = createTempDirectory("catalog")
        writePluginJar(
            workspace.resolve("Lobby-1/plugins/WorldEdit.jar"),
            """
            name: WorldEdit
            main: com.sk89q.Main
            version: "7"
            commands:
              wand:
                permission: worldedit.wand
            permissions:
              worldedit.reload:
                default: op
              worldedit.selection:
                default: true
            """.trimIndent(),
        )
        var now = 0L
        val catalog = PermissionCatalog(CatalogContext(workspace, workspace), clock = { now })

        val entries = catalog.entries()
        val byNode = entries.associate { it.node to it.source }

        // addon declaration wins over the core duplicate
        assertEquals("addon:helix.bans", byNode["helix.bans"])
        assertEquals("core", byNode["helix.panel.login"])
        assertEquals("plugin:WorldEdit", byNode["worldedit.reload"])
        assertEquals("plugin:WorldEdit", byNode["worldedit.selection"])
        assertEquals("plugin:WorldEdit", byNode["worldedit.wand"])
        assertTrue(entries.map { it.node } == entries.map { it.node }.sorted())
    }

    private fun writePluginJar(jar: Path, pluginYml: String) {
        Files.createDirectories(jar.parent)
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.yml"))
            zip.write(pluginYml.toByteArray())
            zip.closeEntry()
        }
    }
}
