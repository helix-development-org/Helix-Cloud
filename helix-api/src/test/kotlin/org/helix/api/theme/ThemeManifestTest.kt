package org.helix.api.theme

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeManifestTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val manifest = ThemeManifest(
        id = "demo",
        name = "Demo",
        tokens = mapOf("accent" to "#ffcf4a"),
        placeholders = mapOf("crown" to "<white><font:demo:icons></font></white>"),
        ranks = listOf(
            ThemeRank("owner", "[Owner]", "#d8382e", 100, badge = ThemeBadge("demo:ranks", "", 38)),
            ThemeRank("default", "[Spieler]", "#c8bca8", 0, default = true),
        ),
        sounds = mapOf("ui.click" to ThemeSound("demo:ui.click")),
        cast = ThemeCast(narrator = "anna", villagers = listOf(ThemeVillager("anna", "Anna", robe = "#8e2a1a"))),
        navigator = ThemeNavigator(places = listOf(ThemePlace("square", "Square", 0, 1, villager = "anna"))),
        cosmetics = listOf(ThemeCosmetic("halo", "head", "Halo", 1001)),
        joinNotices = listOf(ThemeJoinNotice("mods.hint")),
        dashboard = ThemeDashboard(title = "Demo · Panel", defaultMode = "dark"),
    )

    @Test
    fun `manifest survives a json round trip`() {
        val text = json.encodeToString(ThemeManifest.serializer(), manifest)
        assertEquals(manifest, json.decodeFromString(ThemeManifest.serializer(), text))
        val snapshot = ThemeSnapshot("abc", manifest)
        assertEquals(snapshot, json.decodeFromString(ThemeSnapshot.serializer(), json.encodeToString(ThemeSnapshot.serializer(), snapshot)))
    }

    @Test
    fun `a minimal manifest parses with defaults`() {
        val parsed = json.decodeFromString(ThemeManifest.serializer(), """{"id":"x"}""")
        assertEquals("x", parsed.name)
        assertTrue(parsed.ranks.isEmpty())
        assertNull(parsed.cast.narrator)
    }

    @Test
    fun `views resolve over the neutral defaults`() {
        val view = ThemeView.of(manifest, "abc")
        assertEquals("#ffcf4a", view.token("accent"))
        assertEquals(ThemeTokens.NEUTRAL.getValue("success"), view.token("success"))
        assertEquals(ThemeTokens.NEUTRAL.getValue("text"), view.token("unknown"))
        assertEquals("demo:ui.click", view.sounds.getValue("ui.click").id)
        assertEquals(SoundKeys.VANILLA.getValue("ui.error"), view.sounds.getValue("ui.error"))
        assertEquals("<tc:accent><b>{network}</b></tc:accent>", view.placeholders.getValue("wordmark"))
        assertEquals(listOf("owner", "default"), view.ranks.map { it.key })
    }

    @Test
    fun `neutral view has text ranks and no cast`() {
        val neutral = ThemeView.NEUTRAL
        assertEquals("none", neutral.id)
        assertTrue(neutral.ranks.none { it.badge != null })
        assertEquals("<#ff5555>[Owner]</#ff5555> ", neutral.ranks.first().prefix)
        assertEquals("", neutral.ranks.first { it.default }.prefix)
        assertTrue(neutral.cast.villagers.isEmpty())
        assertNull(neutral.content("motd/favicon.png"))
    }

    @Test
    fun `plain replaces badge glyphs by labels`() {
        val view = ThemeView.of(manifest, "abc")
        val prefix = view.ranks.first().prefix
        assertEquals("<white><font:demo:ranks></font></white> ", prefix)
        assertEquals("[Owner] Bob", view.plain("${prefix}Bob"))
        assertEquals("no badge", view.plain("no badge"))
    }

    @Test
    fun `sound keys with a colon play unchanged`() {
        assertEquals("reich:ui.click", SoundKeys.resolve("reich:ui.click", emptyMap())?.id)
        assertEquals("minecraft:ui.button.click", SoundKeys.resolve(SoundKeys.UI_CLICK, emptyMap())?.id)
        assertNull(SoundKeys.resolve("ui.unknown", emptyMap()))
    }
}
