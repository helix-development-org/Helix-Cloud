# helix-addon-mobs

Network-wide mob redesign (addon id `helix.mobs`). Ships as `helix-mobs-<version>.hxa`
(`addon.json`, `addon.jar`, `pack.zip`); the node merges `pack.zip` into the network resource pack.

## Pack contents (`generatePack`)

| Source (`src/pack/`)          | Pack path                                  |
|-------------------------------|--------------------------------------------|
| `resources/cem/*.jem`         | `assets/minecraft/optifine/cem/`           |
| `resources/textures/entity/**`| `assets/minecraft/textures/entity/**`      |
| `pack.mcmeta`                 | `pack.mcmeta` (pack_format 46, like the other addon packs) |

- Restyled textures work for every client (vanilla shapes).
- The cartoon models (`.jem`) need **OptiFine** or **Fabric + Entity Model Features** (EMF/ETF).
  Part names, pivots and UVs stay vanilla, so vanilla animations and texture variants keep working.

## Generators (`tools/mobs/`)

- `cem.py <cem_templates.json> <out>` – builds the `.jem` models from the CEM templates for 1.21.11:
  bigger heads, exaggerated facial features (design concept §13).
- `jobs.py <cem_templates.json> <vanilla-jar-dir> <out>` – maps every vanilla mob texture to its CEM
  model and lists the box-UV face rectangles for the Blockbench toon pass (ink edges, top/bottom light,
  design concept §4).

Assets are generated once and committed; the Gradle build only packages them.

## Join hint

On a player's first network join (remembered in addon storage) the addon sends the translatable
`hint` message (`lang/de-DE.json`, `lang/en-EN.json`) explaining which client mods show the models.
Configure with `mobs.hint <on|off|once|always>`; `mobs.show` prints the current setting.
