# Changelog

## 0.27.1 — 2026-07-22

### Fix: Addon-Panels im neuen Dashboard
- Die Addon-Seiten (bans, economy, permissions, tablist, chat, discord) waren
  im React-Dashboard kaputt. Ursache lag im Host-`AddonPanelView`, nicht in den
  Panels: das Bridge-Runtime wurde **nach** dem Panel-Script injiziert (also war
  `Helix` beim Ausführen undefiniert), `Helix.ready`/`Helix.toast` fehlten, die
  Panel-CSS-Klassen (`card`, `btn`, `badge`, …) wurden nicht bereitgestellt und
  `prompt()` scheiterte an fehlendem `allow-modals`.
- Behoben zentral im Host: Runtime wird **vor** dem Panel-HTML geladen und
  bietet `Helix.action/ready/toast`; ein vollständiges, an shadcn angelehntes
  Panel-Stylesheet (Light/Dark passend zum Dashboard) wird injiziert; die
  iframe-Sandbox erlaubt jetzt Modals. Alle sechs Panels laufen damit ohne
  Änderung an den Addons wieder korrekt.

## 0.27.0 — 2026-07-22

### Dashboard-Rewrite auf React + shadcn/ui
- Das Web-Dashboard ist komplett neu als **React + Vite + Tailwind +
  shadcn/ui**-App (`helix-dashboard/`) gebaut — modernes, konsistentes UI mit
  Sidebar, Cards, Tables, Dialogen, Badges und Recharts-Graphen.
- Der Gradle-Build von `helix-node` baut das Frontend (`pnpm build`) und
  bündelt es in die `Launcher.jar`; der Launcher liefert das Dashboard
  unverändert unter `/` aus (voll selbst-enthalten, keine externen CDNs).
- Feature-Parität: MC-Account-Login + Admin-Token, permission-gefilterte
  Navigation, Overview mit Trend-Graphen, Tasks (+ Anlegen), Services mit
  interaktiver Konsole, Players, Proxy, Events, Logs, Audit, Addons,
  Schedules, Messages (mehrzeilig), Settings sowie Addon-Panels (iframe +
  postMessage-Action-Bridge).

## 0.26.0 — 2026-07-21

### Security-Härtung
- **HTTPS/TLS** für Control-API und Dashboard: `control.tlsKeystore` (PKCS12)
  setzen → der Server läuft über HTTPS statt HTTP.
- **Login-Rate-Limiting**: `POST /auth/request-code` ist pro Spieler auf einen
  Code alle 30 s begrenzt (Verify bleibt bei max. 5 Versuchen pro Code) —
  schützt vor Code-Spam an Spieler und Brute-Force.

## 0.25.0 — 2026-07-21

### Geplante Aufgaben (Scheduler)
- Neuer Job-Scheduler: führt beliebige Actions per **Intervall**
  (`everyMinutes`) oder **täglich** (`dailyAt = HH:mm`) aus — z.B. Announcements
  (`player.broadcast`), Wartung schalten (`proxy.maintenance`) usw.
- Jobs werden über den zentralen Storage persistiert (überleben Neustarts,
  funktionieren in allen Storage-Modi) und in der neuen Panel-Seite
  **Schedules** verwaltet (Permission `helix.panel.schedules`); jeder Job kann
  mit „Run now" sofort ausgelöst werden.
- Routen `GET/POST /schedules`, `DELETE /schedules/{id}`, `POST /schedules/{id}/run`.

## 0.24.0 — 2026-07-21

### Metrik-Historie & Graphen
- Die Node sampelt alle 15 s Netzwerk-Metriken (Online-Player, Slots, laufende/
  gesamte Services, durchschnittliche TPS) in einen In-Memory-Ringpuffer
  (~12 h) und liefert sie über `GET /metrics?limit=` (Permission
  `helix.panel.overview`).
- Die Overview-Seite zeigt jetzt einen **Trends**-Bereich mit Sparkline-
  Graphen (Players, Services, Avg TPS) statt nur Live-Zahlen.

## 0.23.0 — 2026-07-21

### Player-Verwaltung im Panel
- Neue Dashboard-Seite **Players** (Permission `helix.panel.players`): alle
  Online-Spieler mit UUID, Proxy und Online-Dauer, plus Aktionen **Message**,
  **Kick** und **Ban** (Dauer + Grund) direkt aus dem Web.
- Neue gated Routen `GET /players` und `POST /players/{name}/message|kick|ban`,
  die serverseitig die bestehenden Actions (`player.message`, `player.kick`,
  `ban.set`) aufrufen — kein direkter Zugriff auf `/actions` nötig.

## 0.22.0 — 2026-07-21

### Interaktive Service-Konsole
- Neue Konsole pro Service im Dashboard: das Logs-Fenster hat jetzt eine
  Eingabezeile — Befehle (z.B. `say hi`, `list`) werden an die Server-Konsole
  gesendet, die Ausgabe erscheint sofort in den Logs.
- Node schreibt den Befehl in den stdin des Wrapper-Prozesses (den der Server
  erbt): neues `ServiceHandle.sendCommand`, `ServiceManager.sendCommand`, Route
  `POST /services/{id}/command` (Permission `helix.panel.services`, auditiert).
- Prozess-Executor voll unterstützt; Docker-Services antworten (noch) mit
  „console not supported" (stdin-Anbindung folgt separat).

## 0.21.0 — 2026-07-21

### MongoDB als Storage-Backend
- Neuer Storage-Modus `mongodb` neben `json` und `postgres`
  (`[storage] mode = "mongodb"`, `url`, `database`). Addon-Storage liegt in
  der Collection `addon_storage` (Dokumente mit zusammengesetztem `_id`
  `{a: addonId, k: docKey}`), der Audit-Log in der Collection `audit_log`.
- Wie bei postgres öffnet die Node genau **einen** gemeinsamen MongoDB-Client,
  den Addon-Storage und Audit-Log teilen; er wird beim Shutdown geschlossen.
- Backend-Auswahl in einer `StorageBackend`-Abstraktion gebündelt (ersetzt die
  bisherige postgres-spezifische Verdrahtung): sie besitzt die DB-Ressource und
  liefert `StorageProvider` + Audit-`AuditSink` für den gewählten Modus.

## 0.20.0 — 2026-07-21

### Schönere, konfigurierbare Disconnect-Screens
- Alle Trennungs-Screens (Ban permanent/temporär, Kick, Maintenance, Netzwerk
  voll) sind jetzt im Web-Panel (Messages-Seite) **mehrzeilig** editierbar und
  im **MiniMessage**-Format — Farbverläufe (`<gradient>`), Hex-Farben,
  `<bold>` usw.; Legacy-`&`-Codes bleiben unterstützt.
- Universelle Placeholder auf **jedem** Screen (`{player}`, `{network}`,
  `{server}`, `{online}`, `{max}`, `{date}`, `{time}`) plus domänenspezifische
  (`{reason}`, `{remaining}`, `{expiry}`, `{duration}`, `{moderator}`).
- Verteilt pro Addon: Ban-Screens im Bans-Addon (`banned`/`banned.temp`),
  Kick-Screen im Moderation-Addon (`kick.screen`); Proxy-Screens
  (`maintenance`/`server_full`) im neuen node-eigenen `proxy`-Bundle.
- Neuer `[network] name`-Config-Wert für den `{network}`-Placeholder.
- Velocity-Bridge rendert Screens via MiniMessage (multi-line), inkl. neuem
  „Netzwerk voll"-Screen; die Messages-Seite nutzt jetzt mehrzeilige Textfelder.

## 0.19.0 — 2026-07-20

### In-Game-Commands
- Neuer `/helix`-Command (Permission `helix.admin`): Addons anzeigen
  (`/helix addons`), aktivieren/deaktivieren (`/helix enable|disable <id>`)
  und neue `.hxa` live nachladen (`/helix reload`).
- Addon-Actions werden zu In-Game-Commands: `/bans …` (Permission
  `helix.bans`) und `/permissions …` (Permission `helix.permissions`) als
  Dispatcher auf die bestehenden `ban.*`/`perm.*`-Actions.
- `AddonBase.action(...)` unterstützt jetzt `playerCommand` und `permission`;
  die Permission jedes Player-Commands wird über `GET /internal/permission-nodes`
  auch nativ ausgewertet (OP/LuckPerms funktionieren ohne Permission-Addon).

### Chat: Name über dem Kopf & Tablist
- Das vom Chat-Addon aufgelöste Display-Profil (Prefix/Suffix/Farbe) färbt
  nun auch den Namen **über dem Spielerkopf** (per Scoreboard-Team) und den
  **Tablist-Namen** — nicht mehr nur die Chat-Zeile.

## 0.18.0 — 2026-07-20

### Web-Panel-Login per Minecraft-Account
- Der Panel-Login ersetzt das reine Token-Feld durch einen Minecraft-Login:
  Name eingeben → ein Code wird dem Spieler **ingame** zugeschickt → Code im
  Panel eingeben → Session. Neue Endpunkte `POST /auth/request-code`,
  `POST /auth/verify` (öffentlich) sowie `GET /auth/me`, `POST /auth/logout`.
- Session-Tokens (In-Memory, TTL konfigurierbar) neben dem statischen
  Admin-Token, das als Notlogin/Bootstrap und für Bridges/Wrapper gültig bleibt.
- **Permission-gated & feature-scoped**: Login erfordert `helix.panel.login`;
  jede View und jede Addon-Seite ist über `helix.panel.<view>` bzw.
  `helix.panel.addon.<id>` abgesichert. Das Panel zeigt nur erlaubte
  Bereiche; der Server erzwingt sie zusätzlich (`403`). `/internal/*` ist
  ausschließlich dem Admin-Token vorbehalten.

### Permissions unabhängig vom Addon (`PermissionProvider`)
- Neuer `PermissionProvider`-Contract und ein zentraler `PermissionService`:
  Permissions hängen nicht mehr am Permission-Addon.
- **Default = natives MC-Permission-System** — die Proxy-Bridge wertet die vom
  Node über `GET /internal/permission-nodes` gemeldeten Nodes beim Join via
  `player.hasPermission` aus und meldet sie; die Node cached sie pro Spieler.
- Ein aktives Permission-Addon **überschreibt** den Default vollständig. Damit
  läuft das gesamte Netzwerk auch ohne Permission-Addon.

## 0.17.0 — 2026-07-20

### Audit-Log in der Datenbank
- Der Audit-Trail wird jetzt im `postgres`-Storage-Modus in der Datenbank
  gespeichert (Tabelle `audit_log`) statt in `Helix/audit/audit.jsonl`.
  Damit liegt bei zentralem Storage der komplette Trail zentral in Postgres.
- Neue Abstraktion `AuditSink` mit zwei Implementierungen: `FileAuditSink`
  (JSONL, Standard bei `mode = "json"`) und `PostgresAuditSink` (Tabelle
  `audit_log`, Standard bei `mode = "postgres"`). `AuditLog` schreibt an den
  Sink und lädt beim Start die letzten Einträge zurück — der Trail überlebt
  Neustarts in beiden Modi.
- Node öffnet im Postgres-Modus **einen** gemeinsamen Connection-Pool
  (`PostgresPool`), der von Addon-Storage und Audit-Log geteilt und beim
  Shutdown geschlossen wird.

## 0.16.0 — 2026-07-20

### Vollständiger Audit-Log
- Neuer durabler Audit-Trail (`AuditLog`): erfasst **jeden** HTTP-Request
  (Methode, Pfad, Status, Actor), **jede** Action-Invocation (Quelle,
  Argumente, Ergebnis), Auth-Versuche (inkl. abgelehnter 401),
  Service-Lifecycle, Player-Join/Leave, Moderation und Node-Lifecycle.
- Persistiert als append-only `Helix/audit/audit.jsonl` (überlebt
  Neustarts, wird beim Start zurückgeladen) plus In-Memory-Ringpuffer.
- Endpoint `GET /audit?limit=&category=` und neue Dashboard-Seite
  **Audit** mit Kategorie-Filter und Outcome-Färbung.

## 0.15.0 — 2026-07-20

### Task-Konfiguration & Pro-Task-Addons im Dashboard
- Jeder Task hat eine eigene Detailseite (aus der Tasks-Liste geöffnet):
  vollständige Konfiguration bearbeiten (Version, Executor, Kind, Min/Max,
  Speicher, Ports, Autoscale, Templates, JVM-Args) und speichern.
- **Pro-Task-Addon-Aktivierung**: pro Task lassen sich Addons einzeln an-/
  abschalten (`TaskDefinition.disabledAddons`), verwaltet über Toggles auf
  der Task-Seite.
- Service-spezifische Addon-Effekte respektieren das: Bridge-Values
  (`chat.format`, `tablist.*` …) werden pro Service nach dem Task gefiltert
  (`/internal/bridge-values?serviceId=…`), und Addons können
  `context.isActiveForTask(task)` abfragen. Netzwerkweite Addons bleiben
  global. Verifiziert: Task mit deaktiviertem Chat-Addon erhält kein
  Chat-Format, Tablist bleibt.

> Versionierung: Helix-Cloud ist noch in der Pre-1.0-Entwicklung. Die
> frühere `1.x`-Zählung war verfrüht und wurde auf `0.x` umgestellt
> (Meilensteine `v1.0.0`…`v1.9.0` → `v0.1.0`…`v0.10.0`). `1.0.0` kommt,
> wenn die Plattform wirklich stabil und feature-complete ist.

## 0.14.0 — 2026-07-20

### Zentraler Storage-Provider (JSON oder PostgreSQL)
- Addons speichern über einen einheitlichen Dokument-Store
  `context.storage()` statt selbst Dateien zu schreiben. Der Node wählt
  das Backend zentral über `node.toml [storage] mode = json | postgres`.
- `json` (Default): eine `<key>.json`-Datei je Dokument im Addon-Daten-
  ordner (rückwärtskompatibel zu den bisherigen Dateien).
- `postgres`: alle Addons teilen sich eine Tabelle `addon_storage
  (addon_id, doc_key, value)` in einer gepoolten Datenbank (HikariCP).
- Alle Addon-Daten laufen darüber: Bans, Economy, Friends, Permissions,
  Warns, Chat/Tablist/Discord-Config und die Messages. Gegen echtes
  PostgreSQL 16 verifiziert (schreiben, lesen, Neustart-Persistenz).
- Neue API: `AddonStorage` + `context.storage()`.

## 0.13.0 — 2026-07-20

### Instant statt Polling (Latenz)
- Die Velocity-Bridge holt Commands, Routing und Player-Command-
  Registrierung nicht mehr im 5s-Takt, sondern per **Long-Poll**: die
  Node antwortet in dem Moment, in dem etwas passiert. Nur der Heartbeat
  läuft noch auf dem 5s-Timer.
- Neuer `ProxyEventHub` weckt wartende Long-Polls sofort; Versionierung
  für Routing und Player-Command-Katalog lässt die Bridge gezielt neu
  laden. Neuer Endpoint `GET /internal/poll`.
- Ergebnis: Kicks, Nachrichten, Broadcasts, Routing-Updates und neue
  Commands erreichen den Proxy in **~30 ms** statt bisher bis zu 5s
  (im Schnitt ~2,5s). Gemessen: Command-Latenz 30 ms, Routing-Latenz 37 ms.

## 0.12.0 — 2026-07-20

### Aufräumen der Codebase
- Konvention: genau eine Klasse/Objekt/Interface pro Datei — alle
  Multi-Typ-Dateien aufgeteilt (API-DTOs, Node-Services, Control-DTOs,
  Addon-Models/Stores, Bridge-Helper, Test-Fakes), Dateinamen = Typname.
- Ungenutzte Imports entfernt, kleinere Formatierung; keine Verhaltens-
  oder API-Änderung.

## 0.11.0 — 2026-07-20

### Addon-API vollständig aus Java nutzbar
- Factory-Methoden sind `@JvmStatic` (`ActionResult.ok/error`,
  `JoinDecision.allow/deny`, `ProxyCommand.kick`), Konstruktoren mit
  Defaults `@JvmOverloads` (`ActionDescriptor`, `ActionInvocation`), und
  `Messages.format` hat eine `Map`-Überladung. Handler waren als
  SAM-Interfaces bereits als Java-Lambdas nutzbar.
- Verifiziert durch ein reines Java-`.hxa`, das in der Node lädt und
  dessen Action läuft.

## 0.10.0 — 2026-07-20

### Konfigurierbare Addon-Nachrichten
- Neue Infrastruktur: Addons deklarieren ihre Texte über
  `AddonContext.messages(defaults)`; persistiert pro Addon in
  `Helix/addons/data/<addon>/messages.json`, live editierbar, Änderungen
  wirken ohne Neustart. Neue Keys werden bei Updates ergänzt, ohne
  geänderte Werte zu überschreiben.
- Neue Dashboard-Seite **Messages**: alle Nachrichten aller Addons an
  einem Ort bearbeiten (Save/Reset), inkl. Platzhalter- und Farbcode-Hilfe.
- Endpoints `GET /messages` und `POST /messages`.
- Alle player-facing Texte auf das System umgestellt: Friends (18),
  Moderation (8), Economy (6), Team Utils (6), Bans (4), Discord (2) —
  44 einstellbare Nachrichten. (Chat-Format und Tablist-Header/-Footer
  waren bereits über eigene Actions konfigurierbar.)

## 0.9.0 — 2026-07-20

### Addon-Seiten im Dashboard
- Neuer Mechanismus: Addons können eigene Dashboard-Seiten beisteuern
  (`AddonContext.registerDashboardPanel` / `AddonBase.panel(...)`). Die
  Seite (HTML/JS) wird in einem sandboxed iframe gerendert; über die
  injizierte Brücke `Helix.action()` ruft sie Actions auf, ohne dass der
  Control-Token je in den Panel-Code gelangt. CSS-Variablen und
  Basisklassen des Dashboards werden injiziert, Theme-Wechsel wird
  durchgereicht. Panels erscheinen unter „Extensions" und werden beim
  Disable entfernt.
- Endpoints `GET /panels` und `GET /panels/{id}`.
- **Sechs Addons bekommen eine Seite**: Permissions (Gruppen/Player
  verwalten), Economy (Guthaben admin-seitig anpassen), Bans (bannen,
  Übersicht, pardon), Chat (Format + Prefix-Regeln), Tablist
  (Header/Footer mit Live-Preview), Discord (Bot-Status + Config).
  Dazu die JSON-Export-Actions `perm.export`, `eco.export`, `ban.export`,
  `chat.export`, `tablist.export`, `discord.config.get`/`discord.config.set`.

## 0.8.0 — 2026-07-20

### Addons ohne Neustart nachladen
- Neue Action `addon.list.reload`: scannt `Helix/addons/` und lädt jede
  noch nicht geladene `.hxa` live nach (aktiviert sie samt Actions,
  Player-Commands, Gates usw.), ohne den Launcher neu zu starten. Bereits
  geladene Addons bleiben unangetastet, kaputte Pakete werden übersprungen.
- `AddonManager.reload()` als zugrundeliegender Mechanismus.

## 0.7.0 — 2026-07-20

### Komplett neues Web-Dashboard
- Neu gestaltetes, modernes UI: Icon-Sidebar mit Gruppen, Topbar mit
  Live-Verbindungsstatus, Stat-Tiles, Cards, Statusbadges, Player-Bars,
  Toggle-Switches, segmentierte Filter, Light/Dark — self-contained
  (keine externen Assets).
- Neue Seiten: **Proxy** (Maintenance-Schalter, Proxy-Liste,
  Backend-Routing mit aufgelösten Adressen), **Events** (Timeline mit
  Kategorie-Filter), **Logs** (Live-Terminal mit Suche und Pause),
  **Settings** (Node-Infos, Token kopieren, Action-Konsole + Action-Liste).
- Overview mit Live-Aktivität und Service-Health, Tasks/Services/Addons
  überarbeitet.

### Neue Observability-Endpoints
- `GET /logs` — Node-Log über einen Ringpuffer, der `stdout`/`stderr`
  mitschneidet (erfasst auch Library-Ausgaben).
- `GET /events` — Event-Timeline aus Service-Lifecycle, Player-Join/Leave,
  Moderations-Notifications, Proxy- und Task-Änderungen.
- `GET /proxy` und `POST /proxy/maintenance` — Proxy-Übersicht und
  Maintenance-Steuerung.

## 0.6.3 — 2026-07-19

### Fix: Velocity startet nicht („Your configuration is invalid")
- Die generierte `velocity.toml` ist jetzt vollständig: mit
  `config-version = "2.7"` und expliziter leerer `[forced-hosts]`-Sektion.
  Vorher füllte Velocity die fehlenden Teile mit seinen Beispiel-Defaults
  auf (`lobby.example.com` → nicht existierende Server) und brach den
  Start ab.
- Paper-Workspaces erhalten eine `spigot.yml` mit `bungeecord: true`,
  damit Legacy-Proxy-Forwarding funktioniert und Paper Spieler vom
  Velocity-Proxy akzeptiert.
- Verifiziert mit echtem Velocity 3.4.0 im Container: Download über die
  PaperMC-API, Start ohne Config-Fehler, Bridge verbindet sich und der
  Service wird `RUNNING`.

## 0.6.2 — 2026-07-19

### Fixes: Docker-Crash-Loop
- **SELinux-Fix**: Workspace-Mount trägt jetzt das `:z`-Label — auf
  Fedora/RHEL (SELinux enforcing) konnte der Container die gemountete
  `Wrapper.jar` nicht lesen und beendete sich sofort mit Exit 1
  (No-Op auf Systemen ohne SELinux).
- **Crash-Loop-Backoff**: crasht ein Service (FAILED), pausiert der
  Auto-Scaler den Task 60 Sekunden statt sofort neu zu starten.
- **Crash-Diagnose**: die letzten 100 Log-Zeilen werden beim Terminieren
  gesichert — Container-Logs vor dem `docker rm`, Prozess-Logs vor dem
  Workspace-Cleanup. FAILED-Services bleiben in `service.list`/Dashboard
  sichtbar (inkl. `service.logs <id>`), bis ihre Id neu vergeben wird;
  das Node-Log druckt die letzte Ausgabe direkt beim Crash.
- Doku: Docker-Executor erfordert `control.host = "0.0.0.0"`.

## 0.6.1 — 2026-07-19

### Fixes
- Tasks mit leerer Version sind nicht mehr möglich: Validierung in
  `TaskDefinition`, in `task.create` (Fehlermeldung nennt die
  konfigurierten Versionen) und im Dashboard-Formular (Pflichtfeld,
  Versions-Default wechselt mit der Plattform).
- Ein ungültiges Task-File (z.B. leere Version) verhindert nicht mehr den
  Node-Boot: `TaskStore.reload` skippt es mit Fehler-Log.
- Auto-Scaler: nach einem fehlgeschlagenen Service-Start gilt pro Task
  ein 60s-Cooldown statt Retry alle 5 Sekunden (kein Log-Spam mehr);
  die Fehlermeldung nennt die Ursache kompakt.
- `PaperMcDownloadResolver` weist leere Versionen mit klarer Meldung ab.

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
