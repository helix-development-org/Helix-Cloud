# Changelog

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
