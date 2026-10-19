# Closed test phase

Before the public release KronlandMC runs as a closed test phase: the network stays in
maintenance mode and only staff and members of the `tester` group can join.

## Switch it on

```
proxy.maintenance on        # CLI/dashboard/Discord; persisted in config/maintenance.json
```

The maintenance flag now survives node cold starts (`config/maintenance.json`), not only
self-restarts. While it is on, the proxy serves the MOTD profile `maintenance`
("Geschlossene Testphase", kronland.de, Discord) and kicks everyone without
`helix.maintenance.bypass` with the screen `velocity.screen.maintenance`. Both texts are
seeds only — existing installations keep their configured values (panel: MOTD, translations).

## Manage testers

The permissions addon seeds the group `tester` (weight 10, prefix `[Tester]` in Diamant-Blau)
if it does not exist yet. Its permissions:

| Permission | Purpose |
|---|---|
| `helix.maintenance.bypass` | join while maintenance is on |
| `feedback.report` | report bugs with `/bug` |
| `feedback.tester` | tester-only feedback features (own reports, points) |
| `tester.tools` | test tools (`/testtools`) during the test phase |

Team members with `tester.manage` use the proxy command:

```
/tester add <player> [duration]   # e.g. 14d; without duration: permanent
/tester remove <player>
/tester info <player>
/tester list                      # all testers with remaining time
```

## Afterwards

At the end of the test phase every former tester keeps the permanent badge permission
`badge.betatester` (granted with the release reset), while the `tester` group is removed.
Bug reports, points and pending release rewards are handled by the feedback addon.
