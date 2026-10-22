package org.helix.api.theme

import org.helix.api.message.GlobalPlaceholders
import org.helix.api.message.LegacyToMini
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeTokensTest {
    private val tokens = mapOf("text" to "#f1e2c2", "accent" to "#ffcf4a", "muted" to "#7a4a24")

    @AfterTest
    fun reset() {
        ThemeTokens.reset()
        GlobalPlaceholders.set("tokentest", "")
    }

    @Test
    fun `token tags become hex colour tags`() {
        assertEquals("<#ffcf4a>Gold</#ffcf4a> and <#f1e2c2>text", ThemeTokens.expand("<tc:accent>Gold</tc:accent> and <tc:text>text", tokens))
    }

    @Test
    fun `short close tag closes the most recently opened token`() {
        assertEquals("<#f1e2c2>a <#ffcf4a>b</#ffcf4a> c</#f1e2c2>", ThemeTokens.expand("<tc:text>a <tc:accent>b</tc> c</tc>", tokens))
    }

    @Test
    fun `nested tokens close in order`() {
        assertEquals(
            "<#7a4a24>(</#7a4a24><#ffcf4a>{online}</#ffcf4a><#7a4a24>)</#7a4a24>",
            ThemeTokens.expand("<tc:muted>(</tc:muted><tc:accent>{online}</tc:accent><tc:muted>)</tc:muted>", tokens),
        )
    }

    @Test
    fun `unknown tokens fall back to the text token`() {
        assertEquals("<#f1e2c2>x</#f1e2c2>", ThemeTokens.expand("<tc:nope>x</tc:nope>", tokens))
    }

    @Test
    fun `literal colours and texts without tokens pass through`() {
        val text = "<#ff0000>red</#ff0000> <gold>vanilla</gold> <click:run_command:'/tc hi'>chat</click>"
        assertEquals(text, ThemeTokens.expand(text, tokens))
        assertEquals("plain", ThemeTokens.expand("plain", tokens))
    }

    @Test
    fun `a stray short close tag is dropped`() {
        assertEquals("a b", ThemeTokens.expand("a </tc>b", tokens))
    }

    @Test
    fun `neutral tokens are used until a theme is installed`() {
        assertEquals("<#ffaa00>x</#ffaa00>", ThemeTokens.expand("<tc:accent>x</tc:accent>"))
        ThemeTokens.install(tokens)
        assertEquals("<#ffcf4a>x</#ffcf4a>", ThemeTokens.expand("<tc:accent>x</tc:accent>"))
        assertEquals("#55ff55", ThemeTokens.current["success"], "missing tokens keep their neutral value")
    }

    @Test
    fun `legacy translation and global placeholders expand tokens`() {
        ThemeTokens.install(tokens)
        assertEquals("<gold><#ffcf4a>x</#ffcf4a>", LegacyToMini.translate("&6<tc:accent>x</tc>"))
        GlobalPlaceholders.set("tokentest", "<tc:accent>T</tc:accent>")
        assertEquals("<#ffcf4a>T</#ffcf4a> <#f1e2c2>y</#f1e2c2>", GlobalPlaceholders.apply("{tokentest} <tc:text>y</tc>"))
    }
}
