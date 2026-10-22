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

## Panel für Tester

Tester sind kein Team: Im Webpanel sehen sie nur zwei eigene Tabs (Rechte `helix.panel.login`,
`helix.panel.addon.testguide`, `helix.panel.addon.bugreport` – bei neuen Installationen in der Gruppe
`tester`, bestehende Gruppen bekommen sie einmalig per Voreinstellung).

1. **Login:** Panel-Adresse öffnen, Minecraft-Namen eingeben; der Code kommt im Spiel als Nachricht
   (der Tester muss online sein) und wird im Panel bestätigt.
2. **Tab „Befehle“:** jeder Ingame-Befehl mit Aufruf (`/clan create <Tag> <Name>`), Erklärung und dem, was
   beim Testen passieren soll – Suche, Filter nach Bereich und Zielgruppe, Klick kopiert den Befehl. Im Spiel
   zeigt `/befehle` einen Link direkt auf diesen Tab (Panel-Adresse aus `admin.panelurl`). Der Katalog liegt in
   `helix-addon-feedback/src/main/resources/testguide/commands.json`; neue Befehle dort nachtragen.
3. **Tab „Bug melden“:** Formular (Bereich, Schwere, Titel, Beschreibung, Screenshot) und „Meine Meldungen“
   mit Status, Verlauf und Team-Antworten. Es gelten dieselben Limits und die Tester-Vereinbarung wie bei
   `/bug`; Meldungen aus dem Panel haben die Quelle `panel` und keinen Server-Kontext (Ort und Logs gibt es nur
   ingame). Exploit-Meldungen sehen nur das Team und der Melder.

Der Team-Tab „Bugs“ (alle Meldungen, Status, Zuständigkeit) braucht `feedback.staff` bzw. den Panel-Tab
`helix.panel.addon.feedback`.

## Afterwards

At the end of the test phase every former tester keeps the permanent badge permission
`badge.betatester` (granted with the release reset), while the `tester` group is removed.
Bug reports, points and pending release rewards are handled by the feedback addon.
