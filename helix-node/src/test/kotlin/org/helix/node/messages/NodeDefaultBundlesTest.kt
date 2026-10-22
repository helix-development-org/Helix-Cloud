package org.helix.node.messages

import org.helix.api.message.LangResources
import org.helix.node.launcher.HelixNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeDefaultBundlesTest {
    private fun bundle(dir: String) = LangResources.load(HelixNode::class.java, dir)

    @Test
    fun `node bundles ship english and german defaults`() {
        listOf("velocity" to 14, "helix" to 8).forEach { (dir, keys) ->
            val loaded = bundle(dir)
            assertEquals(setOf("en", "de"), loaded.keys, dir)
            assertEquals(keys, loaded.getValue("en").size, dir)
            assertEquals(loaded.getValue("en").keys, loaded.getValue("de").keys, dir)
        }
        val network = bundle("network")
        assertTrue("prefix" in network.getValue("en"))
        assertTrue("restart.warn" in network.getValue("de"))
    }

    @Test
    fun `escapes and line breaks survive the move to json`() {
        val velocity = bundle("velocity").getValue("en")
        assertTrue(velocity.getValue("command.server.usage").endsWith("/server \\<name>"))
        assertEquals(2, velocity.getValue("screen.server_full").count { it == '\n' })
        assertTrue(velocity.getValue("command.servers.entry").contains("<click:run_command:'/server {server}'>"))
    }
}

class ThemeTokenGoldenTest {
    /** Kronland-equivalent token values from tools/theme/token-map.json (the former literal colours). */
    private val kronland: Map<String, String> = run {
        val text = java.io.File("../tools/theme/token-map.json").readText()
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        (root.getValue("kronland") as kotlinx.serialization.json.JsonObject)
            .mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }
    }

    @Test
    fun `converted templates render exactly like the former literal colours`() {
        val template = LangResources.load(HelixNode::class.java, "velocity").getValue("en").getValue("screen.server_full")
        val expected = "<#ff5a4a><bold>Network full</bold>\n" +
            "<#f1e2c2><#ffcf4a>{network}</#ffcf4a> is full right now " +
            "<#7a4a24>(</#7a4a24><#ffcf4a>{online}</#ffcf4a><#7a4a24>/</#7a4a24><#ffcf4a>{max}</#ffcf4a><#7a4a24>)</#7a4a24>\n" +
            "<#f1e2c2>Please try again in a moment."
        kotlin.test.assertNotEquals(expected, template, "the default template uses tokens now")
        assertEquals(expected, org.helix.api.theme.ThemeTokens.expand(template, kronland))
    }
}
