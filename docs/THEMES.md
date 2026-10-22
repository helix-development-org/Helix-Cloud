# Themes

Helix is a generic network platform. Everything that makes a network look like *itself* — colours, rank badges,
the wordmark, GUI artwork, sounds, a villager cast, the lobby map, mob models, cosmetics and the dashboard look —
comes from a **theme**. Without a theme Helix renders a neutral look; nothing network-specific is compiled into
Helix.

This document is the contract between the node, the bridges, the Paper components, the dashboard and the
`helix-theme-kit` that builds themes.

## 1. Theme package

An installed theme is a directory `Helix/themes/<id>/` (a build output of `helix-theme-kit build`):

```
theme.json            manifest (section 2)
pack.zip              resource-pack overlay (optional)
rigs.json             villager rig bundle (optional; org.helix.api.theme.rig.RigBundle)
lang/<locale>.json    flat text overlays: {"helix.translations.<owner>.<key>": "…"}
content/<addon>/…     addon content slots, e.g. content/motd/favicon.png
dashboard/theme.css   CSS variable overrides and extra rules (@font-face for its fonts)
dashboard/assets/**   logo, logo mark, favicons, mascot, fonts, textures
```

Every part is optional. A theme may consist of nothing but a `theme.json` with a few tokens.

## 2. `theme.json`

Kotlin model: `org.helix.api.theme.ThemeManifest` (kotlinx.serialization, unknown keys ignored).

```json
{
  "format": 1,
  "id": "example",
  "name": "ExampleMC",
  "version": "1.0.0",
  "network": { "domain": "example.org" },
  "tokens": { "accent": "#ffcf4a", "text": "#f1e2c2", "screen.ink": "#2a160a" },
  "placeholders": {
    "wordmark": "<white><font:example:wordmark></font></white>",
    "wordmark_large": "<white><font:example:wordmark></font></white>",
    "crown": "<white><font:example:icons></font></white>",
    "currency_icon": "<white><font:example:icons></font></white>"
  },
  "ranks": [
    { "key": "owner", "label": "[Owner]", "color": "#d8382e", "weight": 100,
      "badge": { "font": "example:ranks", "glyph": "", "width": 38 } },
    { "key": "default", "label": "[Player]", "color": "#c8bca8", "weight": 0, "default": true }
  ],
  "sounds": { "ui.click": { "id": "example:ui.click", "category": "master" } },
  "cast": {
    "narrator": "anna", "headNamespace": "example", "modelNamespace": "example_villager",
    "speakerFont": "example:speakers",
    "villagers": [ { "id": "anna", "name": "Anna", "role": "Guide", "robe": "#8e2a1a", "ticksPerLetter": 2,
                     "lidRest": 0.05, "speakerGlyph": "",
                     "voice": { "babble": "example:villager.anna.babble", "hmm": "example:villager.anna.hmm" } } ]
  },
  "navigator": {
    "font": "example:navigator", "spriteNamespace": "example", "fogPlace": null,
    "places": [ { "id": "square", "name": "Square", "column": 0, "row": 1, "headColumn": 2, "headRow": 1,
                  "villager": "anna", "match": ["lobby", "hub"] } ],
    "emblems": [ { "id": "swords", "match": ["pvp", "duel"] } ],
    "pickInstanceMatch": ["city"]
  },
  "cosmetics": [ { "id": "halo", "slot": "head", "label": "Halo", "customModelData": 1001, "permission": "" } ],
  "joinNotices": [ { "key": "mods.hint", "mode": "once" } ],
  "dashboard": { "title": "ExampleMC · Panel", "themeColor": "#3b2617", "defaultMode": "dark",
                 "logo": "logo-256.png", "logoMark": "icon-64.png", "mascot": "mascot.svg",
                 "favicon16": "favicon-16.png", "favicon32": "favicon-32.png" }
}
```

| Section | Meaning | Without theme |
|---|---|---|
| `tokens` | colour tokens (section 5) | `ThemeTokens.NEUTRAL` |
| `placeholders` | global placeholders `{wordmark}`, `{wordmark_large}`, `{crown}`, `{currency_icon}` | wordmark = `<tc:accent><b>{network}</b></tc:accent>`, icons empty |
| `ranks` | default permission groups: label, colour, weight, optional badge glyph | text prefixes `[Owner]`, `[Admin]`, `[Mod]`, default player |
| `sounds` | semantic sound key → sound (section 6) | `SoundKeys.VANILLA` |
| `cast` | villager cast for NPCs, speaker heads and voices; `narrator` speaks on empty screens | no cast, empty screens without speaker |
| `navigator` | lobby map places, emblems and keyword matching | plain mode list |
| `cosmetics` | cosmetics catalogue (models in the pack) | empty |
| `joinNotices` | translation keys of owner `theme` shown on join | none |
| `dashboard` | dashboard title, theme colour, default mode, images | neutral Helix branding |

`ThemeView` (`AddonContext.theme()`, `ThemeSnapshot.view()`) resolves every section over the neutral defaults.

## 3. Selecting a theme

`node.toml`:

```toml
[theme]
id = ""        # "" = automatic, "none" = neutral, "<id>" = Helix/themes/<id>
```

- `""` (default): the theme is used when **exactly one** directory exists in `Helix/themes`; otherwise neutral.
- `"none"`: neutral, even when themes are installed.
- `"<id>"`: that theme; when it is missing or invalid the node falls back to neutral, logs a warning and shows a
  panel notification. A broken theme never stops the node.
- The node computes a content hash of the selected theme. Changing the theme needs a node restart (v1).

## 4. Precedence

| Area | Order (later wins) |
|---|---|
| Resource pack | addon packs (sorted by addon id, first wins on conflicts) → **theme `pack.zip`** → `Helix/packs/dynamic/` (operator files). JSON files under `assets/*/lang/*.json` are merged key by key, `assets/*/atlases/*.json` concatenate their `sources`. A theme overriding a font JSON of an addon pack is logged as a warning. `pack.mcmeta` takes the theme name, `pack.png` comes from the theme. |
| Texts | addon / node defaults → **theme `lang/`** → operator overrides (dashboard translations). "Reset" in the dashboard returns to the theme text. |
| Placeholders | neutral defaults → theme `placeholders`. `{network}` and `{prefix}` still come from the network texts and `node.toml`; `{network}` inside theme values is resolved first. |
| Tokens | `ThemeTokens.NEUTRAL` → token defaults declared by addons → theme `tokens`. |

## 5. Colour tokens

Texts reference colours by token: `<tc:accent>Gold</tc:accent>`. `</tc>` closes the most recently opened token.
Unknown tokens fall back to `text`. Literal colours (`<#ff0000>`, `<red>`) stay allowed.

Why `tc` and not `c`: MiniMessage already treats `c` as an alias of `color` (`<c:gold>` silently renders vanilla
gold) and tag names cannot contain dots.

`org.helix.api.theme.ThemeTokens.expand` is a plain text rewrite done **before** MiniMessage parsing
(`<tc:x>` → `<#rrggbb>`, `</tc:x>` → `</#rrggbb>`). It needs no Adventure. Where it runs:

- **Node:** `GlobalPlaceholders.apply` (every `Messages.format`, `/internal/translations`) and the bridge values
  (tokens only — bridge values may be JSON).
- **Clients:** `LegacyToMini.translate` (both bridges, Paper components). Paper code must render through
  `LegacyToMini.translate` (or a helper that calls it) instead of `MiniMessage.deserialize` on raw text.
- **Dashboard:** the MiniMessage preview and editor understand `tc` with the token map of `GET /api/v1/theme`.

Each process holds one installed token map (`ThemeTokens.install`); bridges install the theme's tokens when they
sync (`theme.tokens` bridge value, translations snapshot).

| Token | Neutral | Typical use |
|---|---|---|
| `text` | `#e8e8e8` | body text |
| `accent` | `#ffaa00` | names, values, highlights |
| `muted` | `#888888` | separators, brackets |
| `subtle` | `#cccccc` | secondary lore |
| `disabled` / `disabled.light` | `#aaaaaa` / `#bbbbbb` | inactive entries |
| `primary` | `#5b8cff` | brand colour, chevrons |
| `success` / `error` / `warn` / `info` | `#55ff55` / `#ff5555` / `#ffcc33` / `#55ccff` | status |
| `rarity.normal` / `rarity.special` / `rarity.elite` | `#cccccc` / `#55ccff` / `#cc66ff` | rarities |
| `dark` | `#202020` | dark surfaces |
| `screen.ink`, `screen.title` | `#404040` | text on GUI screens (vanilla container text colour) |
| `screen.faded`, `screen.disabled` | `#6b6b6b`, `#8b8b8b` | secondary/disabled screen text |
| `screen.light`, `screen.white` | `#ffffff` | text on dark screen areas |
| `screen.red`, `screen.green` | `#aa0000`, `#00aa00` | screen pills and states |

`tools/theme/convert-colors.mjs` converts literal hex colours into tokens using `tools/theme/token-map.json`;
`--check` fails while a mapped colour remains. The file's `kronland` section lists token values that reproduce
the former literal colours exactly.

## 6. Sounds

Code plays **semantic keys** (`org.helix.api.theme.SoundKeys`): `ui.click`, `ui.page`, `ui.rustle`, `ui.stamp`,
`ui.coins`, `ui.error`. The theme's `sounds` map decides what plays; without theme the vanilla sounds of
`SoundKeys.VANILLA` play. A key containing `:` is already a sound event id and plays unchanged — plugins compiled
against concrete ids (`reich:ui.click`, `minecraft:…`) keep working.

A theme ships its sound files in its pack with one `assets/<ns>/sounds.json` per namespace (built by the kit
from the folder tree). Villager voices are referenced from `cast.villagers[].voice`.

## 7. Endpoints and bridge values

| Endpoint | Auth | Content |
|---|---|---|
| `GET /api/v1/internal/theme` | bridge token | `ThemeSnapshot` (manifest without dashboard, lang, content) |
| `GET /api/v1/internal/theme/rigs` | bridge token | `rigs.json` (`RigBundle`) |
| `GET /api/v1/theme` | public | `{id, name, hash, network, tokens, dashboard}` for the dashboard (no secrets) |
| `GET /theme/dashboard.css` | public | theme CSS or empty; cache-busted with `?v=<hash>` |
| `GET /theme/assets/{path}` | public | dashboard assets; path traversal rejected |

The node serves the dashboard's `index.html` itself and injects title, `theme-color`, favicons and
`<link rel="stylesheet" href="/theme/dashboard.css?v=<hash>">` before `</head>` (also for the SPA fallback).

Bridge values of owner `theme`: `theme.id`, `theme.hash`, `theme.tokens` (JSON object). A changed hash makes
bridges and Paper components refetch the snapshot.

Node addons read the theme with `AddonContext.theme()`; `content(path)` returns files below `content/`.

## 8. Resource pack rules

- **Reserved paths** (Helix-owned, a theme must not ship them): `assets/helix_guis/font/{ledger,spaces}.json`,
  `assets/helix_guis/font/text_*.json`, `assets/helix_guis/font/screen/**`. The kit refuses them.
- **GUI screen art** keeps Helix's file names and sizes (`assets/helix_guis/textures/gui/ledger/*`,
  `…/item/anim/*`); the kit checks the sizes against `LedgerLayout`.
- **One `sounds.json` and one font JSON per namespace.** Themes use their own namespaces; new themes default to
  `helix_theme:*`.
- **Pinned glyphs:** rank badge glyphs are stored in permission group prefixes in the database. A theme must keep
  every published badge on the same font and codepoint; `helix-theme-kit validate` fails on duplicates or moved
  pins. Wordmark and icon glyphs should stay stable for the same reason (stored texts may contain them).

## 9. Compatibility and migration

- Existing installs keep their stored configs and operator translations; neutral defaults only affect fresh
  installs and texts nobody customised. A network that relied on Helix's former defaults installs its theme and
  applies its configuration on start.
- `org.helix.api.design` (Palette, StaffRanks, RankBadges, Wordmark, Crown) is replaced by the theme model.
- The pack hash changes once when a theme is introduced; clients download the pack once.
- Addons must not hard-code colours, glyphs, sounds or network names: use tokens, `AddonContext.theme()`,
  semantic sound keys and the `{network}` / `{website}` placeholders.

## 10. File ownership of the theme migration (wave 1)

| Agent | Owns |
|---|---|
| **A – node, API, server addons** | `helix-api/**` (beyond the theme package), `helix-node/src/**`, `helix-wire/**`, `helix-bridge-paper/**`, `helix-bridge-velocity/**`, `helix-addon-sdk/**`, node-side addons (permissions, nick, feedback, economy, motd, tablist, scoreboard, chat, moderation, discord, clan, …): `ThemeService`, `[theme]`, pack overlay, theme text layer, placeholders and token expansion on everything the node sends, endpoints, rank seeding from the theme, removal of `org.helix.api.design`, neutral defaults and network identity keys. |
| **B – Paper GUI side** | `helix-gui/**`, `helix-addon-guis(-paper)/**`, `helix-addon-npc(-paper)/**`, `helix-addon-lobby(-paper)/**`, `helix-addon-cosmetics(-paper)/**`, every other `*-paper` module, removal of `helix-addon-mobs`: `GuiTheme`, screen colours as theme getters, sound mapping, data-driven villagers and navigator, neutral GUI art, rendering through `LegacyToMini` (then drop the paths from `skipKotlin` in `tools/theme/token-map.json` and convert them). |
| **C – kit, dashboard, Kronland** | new `helix-theme-kit/**`, `helix-dashboard/**`, `settings.gradle.kts` and the root `build.gradle.kts` (kit module, release bundle), `tools/theme/**`, `/workspace/Kronland/**` (theme sources under `theme/kronland/`, `build-theme.sh`, branding). |
