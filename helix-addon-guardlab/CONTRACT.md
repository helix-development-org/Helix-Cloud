# helix-addon-guardlab – GuardLab contract

GuardLab is the automated IGuard test lab of KronlandMC. A scripted dev client (LabyMod addon,
`helix-labymod-addon`, `tools/guardlab-client.sh`) joins the task **`IGuard-Test`**, plays scenario after
scenario (legit movement, cheats, lag) and the Paper component judges what IGuard reported. The node addon
only holds the settings and the run summaries.

```
client ──(Toxiproxy :25570, lag profiles)──▶ Velocity ──▶ IGuard-Test (GuardLab + IGuard)
   ▲                                                         │ plugins/GuardLab/reports/<ts>-<suite>/
   └──────────────── helix:guardlab (JSON) ◀─────────────────┘ summary → node (guardlab.report.store)
```

## Node actions

| Action | Arguments | Notes |
|---|---|---|
| `guardlab.configure` | `<token\|-> [suite] [autostart]` | Permission `guardlab.admin`. Token 16–128 chars `[A-Za-z0-9_-]`; `-` keeps the token. Published as bridge value `guardlab.settings` = `{token, suite, autostart}`. |
| `guardlab.status` | – | Token (masked), suite, autostart, latest run. |
| `guardlab.run` | `<suite> [player]` | Permission `guardlab.admin`. Publishes `guardlab.request` = `{suite, player, nonce, at}`; the test task runs each nonce once (requests older than 5 min before its start are ignored). |
| `guardlab.report.store` | `<json>` | Bridge-only, only from `IGuard-Test`. Body = `RunSummary` (`suite, player, startedAt, finishedAt, passed, warned, failed, skipped, report, service`). Keeps the newest 50. |
| `guardlab.reports` | `[limit]` | JSON list, newest first. |

Panel: „GuardLab“ (table of the latest runs).

## Plugin channel `helix:guardlab`

One UTF-8 JSON object per plugin message, `type` selects the message.

### Server → client

| `type` | Fields | Meaning |
|---|---|---|
| `hello` | `token`, `suite`, `scenarios` | Sent when the client registers the channel (and every 2 s until sent). The client compares `token` with its own and only then answers `ready` – it never cheats on a server that does not know the token. |
| `scenario` | `id`, `index`, `setupTicks`, `script`, `cheat {mode, params}`, `durationTicks` | Play `script` with the cheat active, then answer `finished`. The server has already teleported and equipped the player. A new `scenario` replaces a running one. |
| `abort` | `reason` | The run stopped (or was refused). Stop playback, release all keys. |
| `done` | `report`, `passed`, `failed` | The run finished; `report` is relative to the server directory. |
| `quit` | – | The client may disconnect (sent 2 s after `done`). |

### Client → server

| `type` | Fields | Meaning |
|---|---|---|
| `ready` | `version`, `brand` | Token verified, ready for scenarios. |
| `started` | `id` | Playback of `id` started (first scripted tick). |
| `finished` | `id`, `ticks` | Playback ended after `ticks` client ticks. |
| `error` | `id`, `reason` | Cannot play `id`. A `reason` starting with `unsupported` (e.g. `unsupported cheat mode nuker`) makes the scenario **skip** instead of fail. |

The dummy client (`GuardDummy`, optional) speaks the same protocol. It receives `scenario` messages with
the id `<id>.dummy` and `cheat.mode = none`; its answers are ignored.

### Script steps

`script` is a list of steps, played in order. Every field except `ticks` is optional; fields not given
keep their previous state except `keys` (released when absent) and `attack`/`use`/`place` (stop).

| Field | Type | Meaning |
|---|---|---|
| `ticks` | int 1–2400 | Step length in client ticks. |
| `keys` | list of `forward`, `back`, `left`, `right`, `jump`, `sprint`, `sneak` | Keys held during the step. |
| `yaw` | number or string | Number = absolute yaw at step start (0 = south/+z). String `"+n"`/`"-n"` = turn by n degrees **per tick**. |
| `pitch` | number | Absolute pitch at step start. |
| `lookAt` | `"target"` | Aim at the nearest attack target (dummy player or target mob) every tick. |
| `attack` | `{cps, jitter, target}` | Click attack at `cps` (± `jitter`). `target`: `entity` (what the crosshair hits) or `block` – with `cps` 0 the attack key is **held** (mining). |
| `use` | bool | Hold the use key. |
| `place` | `{below, cps}` | Place the held block `cps` times per second: `below: true` against the block under the feet (bridging), otherwise at the crosshair. |
| `hotbar` | int 0–8 | Select a hotbar slot at step start. |
| `chat` | string | Send once at step start. |

### Cheat modes and parameters

`cheat.mode` is one of the contract modes; `params` are optional. The parameters below are what the
suites use – a client may ignore parameters it does not support (or answer `error: unsupported …`).
Every mode honours `startTick` (cheat inactive before that playback tick).

| Mode | Parameters (suite usage) |
|---|---|
| `none` | – |
| `speed` | `factor` – horizontal speed multiplier (1.8, 1.15). |
| `fly` | `vy` – vertical motion forced every tick (0 = hover). `jumpVy` – only replace the jump impulse (high jump). `airJump: true` – allow jumping in mid-air. `liquidOnly: true` – only while above/in liquid (jesus). Used for fly, spider, high jump, air jump, fast ladder, jesus and elytra fly. |
| `timer` | `rate` – game speed multiplier (1.5, 1.1). |
| `reach` | `range` – attack/interaction distance. `target`: `player` (default) or `block` (interaction reach). |
| `killaura` | `range`, `cps`, `snap: true` (instant rotations), `multi: true` (switch between targets), `target`: `player`, `mob`, `all`. |
| `nofall` | – (claims on-ground while falling). |
| `step` | `height` – step height in blocks. |
| `scaffold` | `cps`, `facing: false` (place without looking at the block), `target: "crosshair"` (fast place at the crosshair instead of below). |
| `fastbreak` | `multiplier` – break speed multiplier. `nuker: true`, `radius` – break every block in range. |
| `autoclicker` | `cps`, `jitter`, `swing: false` (no arm swing packets). |
| `antiknockback` | – (ignores server velocity). |
| `blink` | `holdTicks` – buffer movement packets, then flush. |

Proposed extensions (not used yet; the matching checks are covered by legit scenarios only): `phase`
(noclip → `movement.phase.a`), `omnisprint` (→ `movement.sprintbackwards.a`), inventory clicks while moving
or with an open inventory (→ `inventory.*`, `combat.inventory.a`), brand/fingerprint spoofing
(→ `client.*`), invalid packets (→ `protocol.badpackets.a`).

## Suite file

`helix-addon-guardlab-paper/src/main/resources/suites.json` (override without rebuilding:
`plugins/GuardLab/suites.json` on the test server). `suites` maps ids to scenario ids or category selectors
(`@legit`, `@cheat`, `@lag`). Shipped suites: `all`, `legit`, `cheats`, `lag`, `smoke`, `movement`,
`combat`, `world`.

A scenario: `id`, `title`, `category` (`legit|cheat|lag`), `setup`, `script`, optional `dummyScript`,
`cheat`, `lag` (`none|ping100|ping250_jitter50|loss2|spike`), `setupTicks` (default 60),
`durationTicks` (default script length + 20), `expect`, `covers`.

- `setup`: `spot` (`<station>.<spot>`), `gamemode`, `effects [{type, amplifier, seconds}]`,
  `items [{slot, material, amount, enchants}]` (slots `hotbar0..8`, `offhand`, `head`, `chest`, `legs`,
  `feet`), `allowFlight`, `flying`, `vehicle` (`boat|minecart|horse|seat`), `mob` (spot of an AI-less
  target mob), `dummy` (spot of the dummy client – skipped without one), `knockback {everyTicks, strength,
  upward}`, `pistonPulseTicks`, `resetStation`.
- `expect`: `none: true` (legit) **or** `checks` (any of them) / `all` (every one) reaching `minVl`
  (summed violation rises, default 1), optional `setback: true|false`, `note`.

## Judgement

IGuard's per-check violation levels are sampled every tick through `IGuardApi.snapshot`; every rise counts
as a flag (levels decay, so only rises are summed). Sampling runs from the `scenario` message until 1 s
(2 s with lag) after `finished`.

- **legit**: no rise → pass; rises below the check's `alert-vl` → warn (noise); a rise reaching
  `alert-vl` or a setback → fail.
- **cheat**: an expected check summed ≥ `minVl` → pass, otherwise fail (missed detection).
- no `started` within 5 s, or playback beyond `durationTicks` + 5 s → fail; `error unsupported…`, missing
  dummy, missing Toxiproxy → skip.
- a passing `ping100`/`ping250_jitter50` scenario with less than half the expected ping → warn (client not
  connected through Toxiproxy).
