package org.helix.addons.motd

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.helix.api.action.ActionDescriptor
import org.helix.api.action.ActionHandler
import org.helix.api.action.ActionInvocation
import org.helix.api.action.ActionInvoker
import org.helix.api.action.ActionResult
import org.helix.api.addon.AddonContext
import org.helix.api.addon.JoinGate
import org.helix.api.addon.PermissionResolver

/**
 * Fake context capturing actions and published bridge values.
 */
private class FakeContext(override val dataDirectory: Path) : AddonContext {
    val handlers = mutableMapOf<String, ActionHandler>()
    val bridgeValues = mutableMapOf<String, String>()

    override val actions: ActionInvoker = object : ActionInvoker {
        override fun invoke(invocation: ActionInvocation): ActionResult = ActionResult.ok()

        override fun descriptors() = emptyList<ActionDescriptor>()
    }

    override fun registerAction(descriptor: ActionDescriptor, handler: ActionHandler) {
        handlers[descriptor.name] = handler
    }

    override fun registerJoinGate(gate: JoinGate) {
    }

    override fun registerPermissionResolver(resolver: PermissionResolver) {
    }

    override fun publishBridgeValue(key: String, value: String) {
        bridgeValues[key] = value
    }

    fun run(action: String, vararg args: String): ActionResult =
        handlers.getValue(action).execute(ActionInvocation(action, args.toList()))
}

class MotdAddonTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val context = FakeContext(createTempDirectory("motd"))

    init {
        MotdAddon().onEnable(context)
    }

    @Test
    fun `publishes default profiles on enable`() {
        val published = json.decodeFromString<MotdConfig>(context.bridgeValues.getValue("motd.config"))
        assertTrue(published.normal.line1.contains("{network}"))
        assertEquals(0, published.maintenance.onlinePlayers)
    }

    @Test
    fun `set updates fields and republishes`() {
        assertTrue(context.run("motd.set", "normal", "line1", "&aWelcome", "to", "Helix").success)
        assertTrue(context.run("motd.set", "normal", "max", "500").success)
        assertTrue(context.run("motd.set", "maintenance", "hover", "line one\\nline two").success)
        assertTrue(context.run("motd.set", "normal", "online", "real").success)

        val published = json.decodeFromString<MotdConfig>(context.bridgeValues.getValue("motd.config"))
        assertEquals("&aWelcome to Helix", published.normal.line1)
        assertEquals(500, published.normal.maxPlayers)
        assertEquals(-1, published.normal.onlinePlayers)
        assertEquals(listOf("line one", "line two"), published.maintenance.hover)
    }

    @Test
    fun `rejects unknown profile and field`() {
        assertFalse(context.run("motd.set", "holiday", "line1", "x").success)
        assertFalse(context.run("motd.set", "normal", "icon", "x").success)
        assertFalse(context.run("motd.set", "normal", "max", "many").success)
    }

    @Test
    fun `export round-trips the configuration`() {
        context.run("motd.set", "maintenance", "line1", "&cDown for maintenance")
        val exported = context.run("motd.export").lines.first()
        val config = json.decodeFromString<MotdConfig>(exported)
        assertEquals("&cDown for maintenance", config.maintenance.line1)
    }

    @Test
    fun `import replaces profiles with animation frames`() {
        val payload = """
            {"normal":{"frames":[{"line1":"&6A","line2":"one"},{"line1":"&6B","line2":"two"}],
             "frameIntervalMs":100,"onlinePlayers":-1,"maxPlayers":-1},
             "maintenance":{"line1":"&cDown","line2":""}}
        """.trimIndent().replace("\n", " ")

        assertTrue(context.run("motd.import", payload).success)

        val published = json.decodeFromString<MotdConfig>(context.bridgeValues.getValue("motd.config"))
        assertEquals(2, published.normal.frames.size)
        // interval clamped to the minimum, base lines synced to frame 0
        assertEquals(500, published.normal.frameIntervalMs)
        assertEquals("&6A", published.normal.line1)
        assertEquals(1, published.maintenance.effectiveFrames().size)
    }

    @Test
    fun `import rejects invalid json`() {
        assertFalse(context.run("motd.import", "{oops").success)
    }
}
