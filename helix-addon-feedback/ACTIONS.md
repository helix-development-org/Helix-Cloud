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

- Calls from the CLI, REST/dashboard or other addons are **staff** calls.
- Calls from a Paper/Velocity bridge (`source = BRIDGE`) act **on behalf of a player** given in the payload
  (`viewer`/`actor` = `{uuid, name}`). Staff = the player holds `feedback.staff` (checked by name).
  - Non-staff players only see and comment on **their own** reports.
  - Reports with severity `exploit` are visible to staff and to their reporter only.
- Write actions on status/assignee/duplicate need staff.
- Bridge calls of `feedback.list`/`get`/`comment`/`attach`/`attach.finish` must name the player (`viewer`, `author`,
  trailing `viewerJson` = `{"uuid":…,"name":…}`); without it a bridge `list` answers `forbidden`.
- Malformed calls answer `usage` with the usage line in `lines[1]`.
- `feedback.settings`, `feedback.rewards.table`, `feedback.rewards.grant`, `feedback.attachment` and `feedback.export`
  are not bridge-invocable (dashboard/CLI/addons only).

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
`{id, uuid, nr, title, status, at}` · `PendingReward` = `{id, uuid, reward:{…}, createdAt, claimedAt|null}`

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
| `feedback.rewards.claim` | ✔ | `<uuid> <id>` | `PendingReward` (claimed); `not_found`, `claimed` |
| `feedback.export` | – | – | `{total, byStatus:{…}, bySeverity:{…}, top:[{uuid,name,total}]}` |

Settings (`feedback.settings`): `testphase` (true), `cooldownSeconds` (60), `maxPerDay` (20), `zone`
(Europe/Berlin), `logMinutes` (10), `logMaxLines` (100), `maxAttachmentBytes` (2097152), `agreementVersion` (1),
`points` (`{minor:1, annoying:2, blocking:4, crash:6, exploit:10}`).

Log lines: on `create`/`auto` the node calls `service.logs <context.service> 2000` and keeps WARN/ERROR entries
(with their stack-trace continuation lines) of the last `logMinutes`, at most `logMaxLines`; lists omit `context.logs`.

Notifications to Discord: `context.publishNotification("bugreport", …)` for new reports and status changes;
reports with severity `exploit` use the category `bugreport-staff`.
