package org.helix.api.theme

import kotlinx.serialization.Serializable

/**
 * Resolved manifest of an installed theme (`Helix/themes/<id>/theme.json`, see `docs/THEMES.md`).
 *
 * A theme carries everything that makes a network look like itself: colour tokens, rank badges, global
 * placeholders (wordmark, icons), the sound map, a villager cast, lobby navigator places, a cosmetics catalogue,
 * join notices and the dashboard look. Helix itself only knows the neutral defaults ([ThemeView.NEUTRAL]); every
 * section is optional.
 *
 * @property format manifest format version; this build understands [FORMAT].
 * @property id theme id, equal to its directory name.
 * @property name display name (dashboard title, pack description).
 * @property version free-form theme version.
 * @property network network identity values the theme ships (website domain).
 * @property tokens colour tokens (`accent` → `#ffcf4a`), overriding the neutral values of [ThemeTokens.NEUTRAL].
 * @property placeholders global placeholder values (`wordmark`, `wordmark_large`, `crown`, `currency_icon`).
 * @property ranks rank definitions (label, colour, weight, optional badge glyph).
 * @property sounds semantic sound key (`ui.click`) to the sound that plays.
 * @property cast villager cast (narrator, speakers, voices, models).
 * @property navigator lobby navigator places and emblems.
 * @property cosmetics cosmetics catalogue.
 * @property joinNotices messages shown to players on join (translation keys of owner `theme`).
 * @property dashboard dashboard branding.
 */
@Serializable
data class ThemeManifest(
    val format: Int = FORMAT,
    val id: String,
    val name: String = id,
    val version: String = "",
    val network: ThemeNetwork = ThemeNetwork(),
    val tokens: Map<String, String> = emptyMap(),
    val placeholders: Map<String, String> = emptyMap(),
    val ranks: List<ThemeRank> = emptyList(),
    val sounds: Map<String, ThemeSound> = emptyMap(),
    val cast: ThemeCast = ThemeCast(),
    val navigator: ThemeNavigator = ThemeNavigator(),
    val cosmetics: List<ThemeCosmetic> = emptyList(),
    val joinNotices: List<ThemeJoinNotice> = emptyList(),
    val dashboard: ThemeDashboard = ThemeDashboard(),
) {
    /** Format constants. */
    companion object {
        /** Manifest format this build reads and writes. */
        const val FORMAT: Int = 1
    }
}

/**
 * Network identity values a theme can ship.
 *
 * @property domain public website domain (`example.org`); the node's own `[network] website` wins.
 */
@Serializable
data class ThemeNetwork(val domain: String = "")

/**
 * One rank of the theme.
 *
 * @property key lower-case rank key, also the name of the seeded permission group.
 * @property label plain-text tag used wherever no resource pack is shown, for example `[Owner]`.
 * @property color rank colour as `#rrggbb` (name colour after the prefix).
 * @property weight sort weight; higher sorts first.
 * @property default whether this is the rank of players without a group.
 * @property badge optional badge glyph; without one the rank shows its coloured [label].
 */
@Serializable
data class ThemeRank(
    val key: String,
    val label: String,
    val color: String,
    val weight: Int,
    val default: Boolean = false,
    val badge: ThemeBadge? = null,
) {
    /** MiniMessage colour tag of [color], for example `<#d8382e>`. */
    val colorTag: String get() = "<$color>"

    /**
     * Group prefix of the rank: the badge glyph plus one space, or the coloured label plus one space without a
     * badge; empty for the default rank without badge.
     */
    val prefix: String
        get() = when {
            badge != null -> "${badge.mini} "
            default -> ""
            else -> "<$color>$label</$color> "
        }
}

/**
 * A rank badge glyph. Glyphs are PINNED: group prefixes stored in the database contain the codepoint, so a theme
 * must never move an existing badge to another font or codepoint.
 *
 * @property font font id of the glyph, for example `helix_guis:ranks`.
 * @property glyph the private-use character of the badge.
 * @property width glyph width in pixels (layout hint).
 */
@Serializable
data class ThemeBadge(val font: String, val glyph: String, val width: Int = 0) {
    /** MiniMessage of the glyph alone, white (untinted). */
    val mini: String get() = "<white><font:$font>$glyph</font></white>"
}

/**
 * A sound a semantic key plays.
 *
 * @property id namespaced sound event, for example `minecraft:ui.button.click`.
 * @property category Minecraft sound source (`master`, `neutral`, `voice` …).
 * @property volume playback volume.
 * @property pitch playback pitch.
 */
@Serializable
data class ThemeSound(val id: String, val category: String = "master", val volume: Float = 1f, val pitch: Float = 1f)

/**
 * The villager cast of a theme.
 *
 * @property narrator villager id shown on empty screens; `null` shows no speaker.
 * @property headNamespace item-model namespace of the speaker heads.
 * @property modelNamespace item-model namespace of the rig parts.
 * @property speakerFont font of the speaker head glyphs used in screen titles.
 * @property villagers the villagers.
 */
@Serializable
data class ThemeCast(
    val narrator: String? = null,
    val headNamespace: String = "",
    val modelNamespace: String = "",
    val speakerFont: String = "",
    val villagers: List<ThemeVillager> = emptyList(),
)

/**
 * One villager of the cast.
 *
 * @property id villager id (model and voice key).
 * @property name display name.
 * @property role role shown under the name.
 * @property robe robe colour as `#rrggbb`.
 * @property ticksPerLetter speech speed.
 * @property lidRest resting eyelid position (0..1).
 * @property speakerGlyph glyph of the speaker head in [ThemeCast.speakerFont].
 * @property voice voice part (`babble`, `hmm`) to sound event.
 */
@Serializable
data class ThemeVillager(
    val id: String,
    val name: String,
    val role: String = "",
    val robe: String = "",
    val ticksPerLetter: Int = 2,
    val lidRest: Double = 0.0,
    val speakerGlyph: String = "",
    val voice: Map<String, String> = emptyMap(),
)

/**
 * The lobby navigator of a theme (a drawn map with places). Without places the lobby shows a plain list.
 *
 * @property font navigator font id.
 * @property spriteNamespace item-model namespace of the emblems.
 * @property fogPlace place id rendered as fog (not yet open), or `null`.
 * @property places the map places.
 * @property emblems mode emblems.
 * @property pickInstanceMatch keywords of modes that open the instance picker.
 */
@Serializable
data class ThemeNavigator(
    val font: String = "",
    val spriteNamespace: String = "",
    val fogPlace: String? = null,
    val places: List<ThemePlace> = emptyList(),
    val emblems: List<ThemeEmblem> = emptyList(),
    val pickInstanceMatch: List<String> = emptyList(),
)

/**
 * One place on the navigator map.
 *
 * @property id place id.
 * @property name display name.
 * @property column map tile column.
 * @property row map tile row.
 * @property headColumn tile column of the villager head.
 * @property headRow tile row of the villager head.
 * @property villager villager id standing at the place, or `null`.
 * @property match keywords matched against mode names.
 */
@Serializable
data class ThemePlace(
    val id: String,
    val name: String,
    val column: Int,
    val row: Int,
    val headColumn: Int = 0,
    val headRow: Int = 0,
    val villager: String? = null,
    val match: List<String> = emptyList(),
)

/**
 * A navigator emblem.
 *
 * @property id emblem id (item model name).
 * @property match keywords matched against mode names.
 */
@Serializable
data class ThemeEmblem(val id: String, val match: List<String> = emptyList())

/**
 * One cosmetic of the catalogue.
 *
 * @property id cosmetic id.
 * @property slot slot (`wings`, `head` …).
 * @property label display name.
 * @property customModelData model id of the cosmetic item.
 * @property permission permission needed to wear it; empty = everyone.
 */
@Serializable
data class ThemeCosmetic(
    val id: String,
    val slot: String,
    val label: String,
    val customModelData: Int,
    val permission: String = "",
)

/**
 * A message shown to players on join.
 *
 * @property key translation key of owner `theme`.
 * @property mode `once` (first join only) or `always`.
 */
@Serializable
data class ThemeJoinNotice(val key: String, val mode: String = "once")

/**
 * Dashboard branding of a theme. File names are relative to `dashboard/assets/`.
 *
 * @property title browser title.
 * @property themeColor browser `theme-color`.
 * @property defaultMode `light` or `dark`.
 * @property logo full logo image.
 * @property logoMark small logo mark.
 * @property mascot login page illustration.
 * @property favicon16 16 px favicon.
 * @property favicon32 32 px favicon.
 */
@Serializable
data class ThemeDashboard(
    val title: String = "",
    val themeColor: String = "",
    val defaultMode: String = "light",
    val logo: String = "",
    val logoMark: String = "",
    val mascot: String = "",
    val favicon16: String = "",
    val favicon32: String = "",
)
