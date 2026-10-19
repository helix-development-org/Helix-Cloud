# Release reset (end of the closed test phase)

The node action `release.reset` resets player progress of the test phase in one go and opens the network.
Permission: `release.reset` (Owner). Every addon that owns player progress registers a participant
`<addon>.release.reset <plan|run>` (protocol in `helix-api` `org.helix.api.release`); the node discovers them by name.

## Checklist

1. **Back up the database** – `./backup.sh` of the deploy bundle (pg_dump + worlds). The node additionally zips every
   static game workspace (`backup.create`), but it cannot dump PostgreSQL itself.
2. **Reward table** – Panel „Bugs“ → Einstellungen → Release-Belohnungen (`feedback.rewards.table`).
3. **Dry run** – `release.reset plan`: lists services to stop, workspace paths to wipe per static service and every
   participant's counts (what is deleted, what is kept).
4. **Adapt `config/release.json`** if needed (written with defaults on first use): confirmation phrase and the wipe
   paths per task. Defaults: CityBuild `plots`, `farm`, `farm_nether`, `farm_end`, PlotSquared `*.db`, CityBuild
   plugin state files; MineClash-Game `village_*`. Built spawn worlds stay.
5. **Run** – `release.reset run KRONLAND-RELEASE [--remove-testers] [--keep-maintenance]`, then follow
   `release.status`.

## What happens

1. Maintenance on.
2. CityBuild, MineClash-Game and MineClash-War are paused and stopped (killed after 180 s).
3. Static workspaces are zipped (`Helix/backups/<service>/`); a json-mode data backup is attempted.
4. Participants run:

| Participant | Deleted | Kept |
|---|---|---|
| `eco.release.reset` | crown accounts, ledger, daily counters, parked legacy balances | – (everyone starts with the starting balance) |
| `mineclash.release.reset` | profiles, match history | configuration |
| `citybuild.release.reset` | homes, kit claims, auctions, pickup boxes, plot market, jobs, daily rewards, tutorial, daily counters, offer purchases, forge serials, profile reports, vehicles, pets, discovered stations | configuration, warps, audit log |
| `stats.release.reset` | all stat values and archived seasons | – |
| `clan.release.reset` | clan bank balances (set to 0) | clans, members, roles, tags |
| `perm.release.reset` | `tester` memberships (only with `--remove-testers`) | all groups and grants; testers join group `betatester` (badge `badge.betatester`, prefix `[Beta]`, weight 1) |
| `feedback.release.reset` | – | reports, points, rewards; queues release rewards, grants `badge.betatester` to point holders, closes the test phase |

5. Configured world folders and plugin files of the static services are deleted.
6. Tasks are resumed (a task paused before stays paused); maintenance off unless `--keep-maintenance`.

Also kept: bans and moderation, network settings, translations, feedback data.

## After the release

- Crowns and unlocks of release rewards are paid on the first join (feedback Paper plugin); keys/items on the first
  join of a CityBuild instance (inventory, overflow into the pickup box).
- Existing MOTD/tablist texts of the test phase are database values – switch the MOTD profile in the panel.
