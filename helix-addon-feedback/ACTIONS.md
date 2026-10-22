# helix-addon-feedback – action contract

Bug reports and the closed test phase of KronlandMC (plan 1, sections 2/3/6/8). Every action answers with
`ActionResult`: on success `lines[0]` is JSON (unless noted); on failure `lines[0]` is a machine code and
`lines[1]` an English detail. Timestamps are epoch **milliseconds**. Player references are UUID strings;
names are carried alongside for display.

## Vocabulary

| Field | Values |
|---|---|
| `severity` | `minor` (Kleinigkeit), `annoying` (Störend), `blocking` (Blockierend), `crash` (Absturz), `exploit` (Exploit) |
| `category` | `mineclash`, `citybuild`, `lobby`, `network`, `graphics`, `other` |
| `status` | `new`, `confirmed`, `in_progress`, `fixed`, `closed`, `rejected`, `duplicate`, `not_a_bug` |
| `source` | `ingame`, `discord`, `panel`, `auto` |

Allowed status transitions:

| From | To |
|---|---|
| `new` | `confirmed`, `rejected`, `duplicate`, `not_a_bug` |
| `confirmed` | `in_progress`, `fixed`, `rejected`, `duplicate`, `not_a_bug` |
| `in_progress` | `fixed`, `confirmed` |
| `fixed` | `closed`, `in_progress` |
| `closed` | `in_progress` |
| `rejected`, `duplicate`, `not_a_bug` | `new` |

Points are granted to the reporter when a report enters `confirmed` for the first time (`minor` 1,
`annoying` 2, `blocking` 4, `crash` 6, `exploit` 10; configurable) and revoked when it becomes `rejected`,
`duplicate` or `not_a_bug`. Auto reports never earn points.

## Visibility and callers

- Calls from the CLI, the static admin token and other addons are **staff** calls.
- Dashboard calls (REST with a signed-in player) are staff only when that player holds `feedback.staff` or
  `helix.admin`. Everyone else – a tester in the dashboard – acts as **themselves** (identity from the session, uuid
  resolved by name) with player rights: payload names (`reporter`, `viewer`, `author`, `actor`) are ignored, `create`
  forces `source = panel` and drops the payload `context` (no server logs in panel reports), `mine` always lists the
  caller's own reports.
- REST permissions of the actions: player-facing `create`, `get`, `comment`, `attach`, `attach.chunk`,
  `attach.finish`, `mine`, `top`, `whoami` need `feedback.report`; the workflow and machine actions (`list`,
  `status`, `assign`, `duplicate`, `severity`, `attachment`, `export`, `toollog.list`, `discord.*`, `rewards.claim`,
  `auto`, `notifications*`, `agreement.*`, `rewards.pending`, `toollog`) need `feedback.staff`; `settings`,
  `rewards.table`, `rewards.grant` need `feedback.admin`. Bridge calls are not affected by these nodes.
- Calls from a Paper/Velocity bridge (`source = BRIDGE`) act **on behalf of a player** given in the payload
  (`viewer`/`actor`/`author` = `{uuid, name}`) and are **never staff**, whatever name the payload carries (staff work
  happens in the dashboard).
  - Non-staff players only see and comment on **their own** reports.
  - Reports with severity `exploit` are visible to staff and to their reporter only.
- Write actions on status/assignee/duplicate/severity need staff and are **not bridge-invocable**
  (`feedback.status`, `feedback.assign`, `feedback.duplicate`, `feedback.severity`, `feedback.list`,
  `feedback.toollog.list`, `feedback.settings`, `feedback.rewards.table`, `feedback.rewards.grant`).
- Non-staff `create` and `comment` (bridge, or addon calls with `asPlayer`) need `feedback.report` (checked by name on
  the node) and the current tester agreement: failures `not_tester`, `agreement_required`.
- Non-staff limits (settings): `cooldownSeconds`/`maxPerDay` for reports, `commentCooldownSeconds`/`maxCommentsPerDay`
  for comments (`comment_cooldown` with the remaining seconds, `comment_limit`), `maxAttachmentsPerReport`
  (`attachment_limit`). At most 64 chunked uploads may be open at once (`busy`).
- New automatic reports are capped per service and hour (`maxAutoPerServiceHour`, failure `auto_limit`); known
  hashes still count up.
- Malformed calls answer `usage` with the usage line in `lines[1]`.
- `feedback.settings`, `feedback.rewards.table`, `feedback.rewards.grant`, `feedback.attachment` and `feedback.export`
  and `feedback.toollog.list` are not bridge-invocable (dashboard/CLI/addons only).
- Addon calls (for example the Discord bot) may set `"asPlayer": true` in the JSON of `create`/`comment` (or in the
  trailing `viewerJson` of `attach`/`attach.finish`): the call then runs with the rights and spam limits of the named
  player instead of staff rights.
- `comment` and the trailing `viewerJson` of `attach`/`attach.finish` accept an optional `"via"` (for example
  `"discord"`); it is only echoed in the resulting `FeedbackEvent` so mirrors can skip their own changes.
- JSON payloads may span several arguments (they are joined with spaces from their position on).

## Models (JSON)

`Report` = `{nr, status, severity, category, title, description, reporter:{uuid,name}|null, context:{…},
assignee|null, duplicateOf|null, points, pointsGranted, source, hash|null, count, affected:[uuid], attachments:[Attachment],
discordThread|null, createdAt, updatedAt}`

`context` (filled by Paper, extended by the node): `service`, `task`, `world`, `x`, `y`, `z`, `yaw`, `pitch`,
`gamemode`, `tps`, `mspt`, `ping`, `client:{version, brand, family, labymod}`, `versions:{…}`, `serverTime`
(`HH:mm:ss` local server clock – used to cut the log window; falls back to the `zone` setting), and node-added
`logs:[line]` (WARN/ERROR lines of the reporting service from the last `logMinutes`, max `logMaxLines`).

`Attachment` = `{id, name, mime, size, at}` · `Comment` = `{id, nr, author:{uuid|null,name}, staff, text, at}`
· `ReportEvent` = `{nr, at, actor, type, from|null, to|null, note|null}` · `Notification` =
`{id, uuid, nr, title, status, at}` · `ToolLogEntry` = `{uuid, name, action, details:{…}, service|null, at}` · `PendingReward` = `{id, uuid, reward:{…}, createdAt, claimedAt|null}`

## Actions

| Action | Bridge | Arguments | Answer |
|---|---|---|---|
| `feedback.create` | ✔ | `<json {reporter:{uuid,name}, severity, category, title, description, source?, context?}>` | `Report`; errors `invalid_*`, `cooldown` (detail = seconds left), `daily_limit`, `testphase_closed` |
| `feedback.auto` | ✔ | `<json {hash, title, description, category?, service?, context?, player?:{uuid,name}}>` | `{nr, new:boolean, count}` – deduplicated by `hash` while the report is not `closed` |
| `feedback.list` | ✔ | `[json {viewer?, status?:[…], severity?:[…], category?:[…], reporter?, query?, limit?, offset?, sort?: "new"\|"updated"\|"severity"}]` | `{total, items:[Report without context.logs]}` |
| `feedback.get` | ✔ | `<nr> [viewerJson]` | `{report, comments:[Comment], events:[ReportEvent]}`; `not_found`, `forbidden` |
| `feedback.comment` | ✔ | `<json {nr, author:{uuid?,name}, text}>` | `Comment`; reporter or staff only |
| `feedback.status` | ✔ | `<json {nr, status, actor?:{uuid,name}, note?}>` | `Report`; `invalid_transition`, `forbidden` |
| `feedback.assign` | ✔ | `<json {nr, assignee|null, actor?}>` | `Report` |
| `feedback.duplicate` | ✔ | `<json {nr, of, actor?}>` | `Report` (status `duplicate`, points revoked) |
| `feedback.attach` | ✔ | `<nr> <name> <mime> <base64> [viewerJson]` | `Attachment`; `too_large` (> `maxAttachmentBytes`, default 2 MiB) |
| `feedback.attach.chunk` | ✔ | `<uploadId> <index> <count> <base64>` then `feedback.attach.finish <uploadId> <nr> <name> <mime> [viewerJson]` | chunk: `{received}`; finish: `Attachment` (uploads expire after 10 min) |
| `feedback.attachment` | – | `<id>` | `lines[0]` = mime, `lines[1]` = base64 |
| `feedback.discord.link` | – | `<nr> <threadId\|->` | `Report` with `discordThread` set (`-` clears); used by the Discord bot |
| `feedback.discord.find` | – | `<threadId>` | `Report` mirrored by that Discord thread; `not_found` |
| `feedback.mine` | ✔ | `<uuid> [limit]` | `[Report]` newest first (own reports, incl. exploits) |
| `feedback.notifications` | ✔ | `<uuid>` | `[Notification]` unread |
| `feedback.notifications.ack` | ✔ | `<uuid> [id …]` | `{acked}` (no ids = all) |
| `feedback.top` | ✔ | `[limit=10]` | `[{rank, uuid, name, points}]` |
| `feedback.agreement.get` | ✔ | `<uuid>` | `{version, acceptedVersion|null, accepted}` |
| `feedback.agreement.accept` | ✔ | `<uuid> [version]` | `{accepted:true, version}` |
| `feedback.settings` | – | `[json patch]` | current settings (patch merges first) |
| `feedback.rewards.table` | – | `[json [{minPoints, kronen, keys:[…], items:[…], note}]]` | current table |
| `feedback.rewards.pending` | ✔ | `<uuid>` | `[PendingReward]` unclaimed |
| `feedback.rewards.grant` | – | `<uuid> <json reward>` | `PendingReward` |
| `feedback.rewards.claim` | – | `<uuid> <id>` | `PendingReward` (claimed); `not_found`, `claimed` – staff/addons only; Paper uses `redeem` |
| `feedback.rewards.redeem` | ✔ | `<uuid> <id> <name>` | `PendingReward` (claimed, idempotent: second call `claimed`). Network part: pays `kronen` via `eco.api.deposit <uuid> <n> release-reward <id>` and grants `permissions` via `perm.user.grant`; payout failure reopens the reward (`payout_failed`). CityBuild part: only marked claimed – the CityBuild plugin delivers keys/items. Bridge calls need the player online under that uuid and name (`forbidden`) |
| `feedback.release.reset` | – | `<plan\|run>` | `ReleaseReport` (helix-api `org.helix.api.release`); permission `release.reset`; called by the node's `release.reset` |
| `feedback.toollog` | ✔ | `<uuid> <json {name?, action, details?:{…}, service?}>` | `ToolLogEntry`; `invalid_toollog` (action required) |
| `feedback.toollog.list` | – | `[uuid\|-] [limit=100, max 1000]` | `[ToolLogEntry]` oldest first |
| `feedback.whoami` | – | – | `{name, staff, admin, uuid?}` – the panel user (`actor`), whether it works with staff rights (`feedback.staff`/`helix.admin`/static token) and whether it holds `feedback.admin` |
| `feedback.export` | – | – | `{total, byStatus:{…}, bySeverity:{…}, top:[{uuid,name,total}]}` |

Bridge value `feedback.settings`: the settings below as JSON **without** `rewards`, republished on every
`feedback.settings` patch – Paper components read `testphase`, `cooldownSeconds`, `maxPerDay`, `maxAttachmentBytes`,
`agreementVersion` from it.

Settings (`feedback.settings`): `testphase` (true), `cooldownSeconds` (60), `maxPerDay` (20), `zone`
(Europe/Berlin), `logMinutes` (10), `logMaxLines` (100), `maxAttachmentBytes` (2097152), `agreementVersion` (1),
`points` (`{minor:1, annoying:2, blocking:4, crash:6, exploit:10}`).

Log lines: on `create`/`auto` the node calls `service.logs <context.service> 2000` and keeps WARN/ERROR entries
(with their stack-trace continuation lines) of the last `logMinutes`, at most `logMaxLines`; lists omit `context.logs`.

Notifications to Discord: `context.publishNotification("bugreport", …)` for new reports and status changes;
reports with severity `exploit` use the category `bugreport-staff`.

### `feedback.severity <nr> <severity> [category]` (staff, not bridge-invocable)

Reclassifies a report. Points already granted follow the new severity (the reporter's total is adjusted). Emits
`FeedbackEvent` `{type: "reclassified", from: <old severity>, to: <new severity>, note?: "category a -> b"}`; the Discord
mirror moves the thread between forum and staff channel when the change crosses `exploit`. Answer: `Report`.

## Panel

`panel.html` (tab „Bugs“): list with filters (status, severity, category, task, source, reporter, search, sort, pages),
detail (context, log excerpt, attachments with preview/upload, comments, events, status transitions, assignee,
duplicate, points), „Hinteleportieren“ via `admin.teleport.location @self <service> <world> <x> <y> <z> [yaw] [pitch]`
(helix-addon-admin; the panel user must be online), „Als Markdown kopieren“, tester ranking, test tool log and – with
`feedback.admin` – settings and the release reward table.
## Screenshot channel (Paper ↔ Helix LabyMod addon)

Plugin channel `helix:feedback`, one UTF-8 JSON object per message. The LabyMod addon registers the channel only when
the player allows screenshots; `/bug` offers the screenshot option only to clients listening on it.

- server → client: `{"type":"capture","id":<uploadId>,"maxWidth":1280,"quality":0.8,"maxBytes":<maxAttachmentBytes>}`
  (sent right after `feedback.create` succeeded)
- client → server: `{"type":"chunk","id","i","n","data":<base64 ≤ 21 000 raw bytes>}` …,
  then `{"type":"done","id","bytes","mime":"image/jpeg","width","height"}` or `{"type":"error","id","reason"}`
  (`disabled`, `busy`, `failed`, `too_large`)
- Paper assembles the chunks (timeout 20 s, limit `maxAttachmentBytes` from the `feedback.settings` bridge value) and
  uploads the JPEG with `feedback.attach.chunk` (256 KiB parts) + `feedback.attach.finish <uploadId> <nr>
  screenshot-<nr>.jpg image/jpeg <viewerJson>`.
Machine events: every created report (incl. new auto reports), status change, comment and attachment is published as
`context.publishNotification("feedback-event", <FeedbackEvent JSON>)` with
`FeedbackEvent` = `{type: created|status|comment|attachment, nr, source?, severity?, from?, to?, actor?, note?,
commentId?, attachmentId?, via?}`. The Discord bot mirrors reports into forum threads from these events.

## Release rewards (plan 1, section 8)

`feedback.rewards.table` tiers: `{minPoints, kronen?, keys?:[crateId], items?:[ref], permissions?:[node], note?}`.
Item refs: `forge:<template>`, `model:<catalog id>`, `<material>` or `<material>*<amount>` (CityBuild items);
`perm:<node>` / `cosmetic:<node>` are permission grants (cosmetics unlock by permission).

On `feedback.release.reset run` every player with points gets the highest reached tier, split into up to two
`PendingReward`s (`reward.release = true`, `reward.points`, `reward.tier`, `reward.note`):

- `part: "network"` – `kronen`, `permissions`; the feedback Paper plugin redeems it on the first join anywhere.
- `part: "citybuild"` – `keys`, `items`; the CityBuild plugin redeems it on the first join of any CityBuild instance
  and puts the items into the player's inventory / pickup box.

The run also grants `badge.betatester` (`perm.user.grant`) to every point holder, sets `testphase = false` and
republishes the bridge value. A second run grants nothing new. The profile shows „Beta-Tester“ (badge permission)
and „Bug-Punkte“.
