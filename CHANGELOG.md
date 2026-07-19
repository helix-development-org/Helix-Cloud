# Changelog

## 0.6.0 — 2026-07-19

### Discord-Bot-Addon (`helix-addon-discord`)
- Echter Gateway-Bot auf **Kord**-Basis; die Library ist vollständig ins
  HXA gebündelt, die Plattform bleibt Discord-frei.
- Leitet Notification-Bus-Kategorien (Default: `moderation` — Bans,
  Warns, Kicks) live in den konfigurierten Channel, Farbcodes werden für
  Discord entfernt.
- Channel-Commands: `!status`, `!players`, `!help` und `!run <action>
  [args...]` (nur konfigurierte Admin-User-IDs) — Antworten kommen direkt
  aus dem Action-Contract.
- Actions: `discord.status`, `discord.send <text...>`, `discord.reload`;
  Konfiguration in `discord.json` (wird mit Vorlage angelegt), ohne Token
  bleibt das Addon idle. Verbindungsfehler crashen die Node nicht,
  sondern landen als `discord`-Notification im Bus.

## 0.5.0 — 2026-07-19

### Moderations-Notifications für das Team
- Neuer entkoppelter **Notification-Bus** zwischen Addons:
  `context.publishNotification(category, message)` und
  `context.registerNotificationListener`; Listener werden beim Disable
  aufgeräumt, Fehler in Listenern isoliert.
- Bans-Addon publiziert Ban und Pardon, Moderations-Addon publiziert Kick
  und Warn — alles unter der Kategorie `moderation`.
- Team-Utils leitet `moderation`-Notifications live an alle Online-
  Teammitglieder weiter (`&e[Warn]`, `&c[Ban]`, `&c[Kick]` im Chat).
  Publisher und Subscriber kennen sich nicht.
- SDK: `RecordingAddonContext` zeichnet Notifications auf.

## 0.4.0 — 2026-07-19

### Sechs neue Addons
- **Friends** — `/friend add|accept|deny|remove|list|requests` mit
  Auto-Accept bei Gegenanfrage und Join-Benachrichtigungen an Freunde.
- **Tablist** — konfigurierbarer Header/Footer mit `{online}`/`{max}`,
  netzwerkweit von der Paper-Bridge angewendet.
- **Pretty Chat** — Chat-Format mit `{prefix}{color}{name}{message}` und
  permission-basierten Prefix-Regeln (erste Regel gewinnt).
- **Economy** — Coins, `/balance`, `/pay` mit Empfänger-Benachrichtigung,
  `eco.give/take/set/get`, atomare Transfers.
- **Moderation** — permission-gated `/kick`, `/warn`, `/warns`,
  `/announce`, `/tempban` (delegiert an das Ban-Addon), Warn-Historie.
- **Team Utils** — Teamchat `/tc`, `/team`, Team-Join/Leave-Notifications,
  `team.notify`; Mitgliedschaft über `helix.team.member`.

### Generische Plattform-Mechanismen (addon-agnostisch)
- **Player-Registry**: Proxies melden Join/Leave; Addons sehen
  Online-Spieler und registrieren Listener; `player.list` Action.
- **Spieler-Commands**: Actions mit `playerCommand=true` registriert die
  Velocity-Bridge dynamisch als In-Game-Commands, optional
  permission-gated; Ausführung über `POST /internal/player-command`.
- **Nachrichten**: neue Proxy-Command-Typen `message`/`broadcast` mit den
  Actions `player.message` und `player.broadcast`; Zustellung an gemanagte
  und meldende Proxies.
- **Display-Profile**: Addons liefern Prefix/Farbe pro Spieler
  (`POST /internal/display`); die Paper-Bridge rendert Chat entsprechend.
- **Bridge-Values**: Addons publizieren globale Werte
  (`GET /internal/bridge-values`); die Paper-Bridge wendet
  Tablist-Header/Footer und Chat-Format an.
- **SDK**: `RecordingAddonContext` als Test-Hilfe für Addon-Entwickler;
  `context.hasPermission(...)` für direkte Permission-Fragen.

## 0.3.0 — 2026-07-19

### Permission-System (`helix-addon-permissions`)
- Gruppen mit Gewicht (höheres Gewicht gewinnt Konflikte), Vererbung
  (zyklensicher) und Default-Flag (gilt für Spieler ohne Gruppen);
  persönliche User-Permissions mit höchster Präzedenz.
- Wildcards (`*`, `prefix.*`) und Negation (`-node`, schlägt Grants auf
  gleicher Ebene).
- Actions: `perm.group.create/delete/list/info/grant/revoke/addparent/
  removeparent`, `perm.user.info/addgroup/removegroup/grant/revoke`,
  `perm.check` — Persistenz in `Helix/addons/data/helix.permissions/`.

### Generischer Permission-Mechanismus (addon-agnostisch)
- `AddonContext.registerPermissionResolver` + Aggregation in der Node
  (erteilt, sobald ein Resolver erteilt; ohne Resolver alles verneint).
- Neuer interner Endpunkt `POST /internal/permission-check`.
- Velocity-Bridge: Spieler mit `helix.maintenance.bypass` dürfen während
  der Maintenance joinen.

## 0.2.0 — 2026-07-19

### Ban-System (`helix-addon-bans`)
- Produkt-Addon: Netzwerk-Bans mit Grund und Ablauf (`30m`, `12h`, `7d`,
  permanent), persistiert in `Helix/addons/data/helix.bans/bans.json`.
- Actions: `ban.set`, `ban.pardon`, `ban.list`, `ban.check` — über CLI,
  REST und Dashboard-Konsole nutzbar.
- Gebannte Spieler werden beim Bannen über die neue generische
  `player.kick`-Action von allen aktiven Proxies entfernt.

### Generische Plattform-Mechanismen (addon-agnostisch)
- **Join-Gate**: Addons registrieren Gates über
  `AddonContext.registerJoinGate`; die Velocity-Bridge fragt bei jedem
  Login `POST /internal/join-check`, die Node aggregiert alle Gates
  (Deny-first, fail-open bei Gate-Fehlern, Cleanup beim Disable).
- **Proxy-Command-Queue**: `GET /internal/commands` liefert pending
  Commands (z.B. Kicks) pro Proxy; die Velocity-Bridge pollt sie im
  5-Sekunden-Sync. Neue Built-in-Action `player.kick <player> [reason]`.
- Die Bridges enthalten weiterhin null addon-spezifischen Code.

## 0.1.0 — 2026-07-19

Erste stabile Version von Helix-Cloud: ein CloudNet-artiges
Orchestrierungssystem für Minecraft-Netzwerke mit einer einzigen
`Launcher.jar` als Startpunkt.

### Plattform
- Generisches Task/Service-Modell: Server-Typ (`PAPER`/`VELOCITY`) und
  Version sind Konfiguration (`versions.toml`), aufgelöst über die PaperMC
  Fill-API mit Jar-Cache.
- Persistente (statische) und temporäre (dynamische) Services mit
  automatischer Workspace-Verwaltung, Port- und Id-Vergabe.
- Universeller Wrapper als gemeinsamer Startmechanismus für Prozess- und
  Docker-Execution; Docker mit eigenem Netzwerk und Host-Gateway.
- Auto-Scaler: hält `minServiceCount`, startet bei Slot-Auslastung über
  Schwellwert zusätzliche Services (bis `maxServiceCount`), stoppt leeren
  Überschuss nach `idleStopSeconds`, Auto-Restart nach Service-Ende.

### Interaktion
- Actions als einziger Interaktions-Contract für CLI, REST, Dashboard und
  Addons; interaktive CLI ohne Eigenlogik.
- Bearer-authentifizierte Ktor Control-API (Tasks, Services, Logs,
  Actions, Addons, Heartbeat/Routing für Bridges).
- Komplett neues Web-Dashboard: Token-Login, Live-Übersicht mit
  Maintenance-Toggle und Action-Konsole, Task-Verwaltung, Service-Tabelle
  mit Log-Viewer und Stop/Kill, Addon-Verwaltung; self-contained,
  hell/dunkel.

### Bridges
- Paper-Bridge: Heartbeats mit Playern/Slots/TPS.
- Velocity-Bridge: dynamische Backend-Registrierung aus Routing-Snapshots,
  Initial-Server/Fallback auf den am wenigsten belasteten Lobby-Backend,
  `/lobby` `/server` `/servers`, Maintenance-Enforcement inkl. MOTD.

### Addons
- HXA-Format (`addon.json` + `addon.jar`) mit Classloader-Isolation,
  Lifecycle (`ENABLED`/`DISABLED`/`FAILED`) und automatischem
  Action-Cleanup; Addon-SDK und Referenz-Addon.

### Qualität
- `./gradlew check` grün über alle Module, 100% KDoc-Gate.
- End-to-End-Smoke-Test (`tools/e2e-smoke.sh`): Boot, Auth-Reject,
  Task über REST, Auto-Start durch Scaler, echter Kindprozess mit
  Heartbeats, Routing, Logs, Auto-Restart, Shutdown, Temp-Cleanup.
