# REST-API-Referenz

Basis-URL: `http://<host>:<port>/api/v1` (Default `127.0.0.1:8080`).
Alle Endpunkte (außer `/auth/request-code` und `/auth/verify`) erfordern
`Authorization: Bearer <token>`; ohne gültiges Token antwortet die API mit
`401`. Ein Token ist entweder das statische `control.token` (voller
Admin-Zugriff, auch von Bridges/Wrapper genutzt) oder ein per Minecraft-Login
ausgestelltes Session-Token. Für ein Session-Token wird jede Aktion zusätzlich
gegen die Permissions des Spielers geprüft (`403` bei fehlender Permission);
`/internal/*` ist ausschließlich dem Admin-Token vorbehalten.

Das Dashboard unter `/` ist statisch und nutzt dieselbe API.

## Auth (Web-Panel-Login per Minecraft-Account)

| Methode | Pfad | Beschreibung |
|---|---|---|
| POST | `/auth/request-code` | **Öffentlich.** Body `{"name": "<mcname>"}`. Prüft, ob der Spieler online ist und `helix.panel.login` besitzt, generiert einen Code und schickt ihn ihm ingame. |
| POST | `/auth/verify` | **Öffentlich.** Body `{"name", "code"}`. Prüft den Code und liefert `{token, identity}` — das Session-Token für alle weiteren Aufrufe. |
| GET | `/auth/me` | Der eingeloggte Aufrufer: `{name, admin, views}` (erlaubte Dashboard-Views). |
| POST | `/auth/logout` | Invalidiert das aktuelle Session-Token. |

Login-Ablauf: MC-Name eingeben → Code ingame erhalten → Code eingeben →
Session-Token. Die sichtbaren Views/Panels richten sich nach den Permissions
(`helix.panel.<view>`, `helix.panel.addon.<id>`). Das Admin-Token darf alles.

## Platform

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/platform/overview` | Aggregierte Zähler (Services, Player, Version) |
| GET | `/metrics?limit=240` | Metrik-Historie (Zeitreihe): `epochMs`, `onlinePlayers`, `maxPlayers`, `servicesRunning`, `servicesTotal`, `avgTps`, `avgApiMs` — 15s-Intervall, In-Memory-Ringpuffer |
| GET | `/api-stats` | Control-API-Performance (rollierendes Fenster): `avgMs`, `p95Ms`, `requestsPerMinute`, `errorRate`, `totalRequests` |
| GET | `/logs?tail=300` | Node-Log (Ringpuffer über stdout/stderr-Capture) |
| GET | `/logs/stream` | Node-Log als **SSE-Stream** (`text/event-stream`, eine `data:`-Zeile pro Log-Zeile, Keep-Alive-Kommentare) |
| GET | `/events?limit=200` | Event-Timeline (neueste zuerst): Service-Lifecycle, Player, Moderation, Proxy, Tasks |
| GET | `/audit?limit=300&category=<cat>` | Vollständiger Audit-Log: jeder HTTP-Request, jede Action, Auth-Versuche, Lifecycle (persistiert in `audit.jsonl` bzw. bei `storage.mode = "postgres"`/`"mongodb"` in `audit_log`) |
| GET | `/proxy` | Proxy-Übersicht: Maintenance, Proxies, Backend-Routing |
| POST | `/proxy/maintenance` | Maintenance schalten — Body `{"enabled": true}` |
| GET | `/panels` / `/panels/{id}` | Addon-Dashboard-Seiten (Metadaten / HTML) |
| POST | `/actions` `{"action":"platform.restart"}` | Backend-Restart: Node-Prozess startet neu, Services laufen headless weiter und werden re-adoptiert |
| POST | `/actions` `{"action":"launcher.restart"}` | Launcher-Restart: Services stoppen, frische `Launcher.jar` startet (Update-fähig) |
| GET | `/translations` | Übersetzungs-Store: `{languages, defaultLanguage, entries:[{key, values, defaults}]}` — flache Keys `helix.translations.<owner>.<key>`, Werte/Defaults je Sprache |
| POST | `/translations` | Übersetzung setzen/anlegen/zurücksetzen — Body `{key, language, value}` oder `{key, language, reset:true}`; neue Keys unter `helix.translations.custom.*` |
| DELETE | `/translations/{key}` | Custom-Key löschen (Keys mit Code-Default sind nur resetbar) |
| POST | `/translations/languages` | Sprache anlegen (`{language:"fr"}`) oder Default setzen (`{language:"de", default:true}`) |
| DELETE | `/translations/languages/{lang}` | Sprache entfernen (Default-Sprache nicht entfernbar) |

## Tasks

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/tasks` | alle Tasks |
| GET | `/tasks/{name}` | ein Task |
| PUT | `/tasks/{name}` | Task anlegen/ändern (Body: Task-JSON, `name` muss zur URL passen) |
| DELETE | `/tasks/{name}` | Task löschen (nur ohne aktive Services) |
| POST | `/tasks/{name}/services` | neuen Service starten → `201` mit ServiceInfo |

## Services

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/services` | alle Services |
| GET | `/services/{id}` | ein Service |
| POST | `/services/{id}/stop` | graceful stop |
| POST | `/services/{id}/kill` | sofort beenden |
| GET | `/services/{id}/logs?tail=50` | neueste Log-Zeilen |
| GET | `/services/{id}/logs/stream` | Service-Log als **SSE-Stream** (Live-Konsole) |
| POST | `/services/{id}/command` | Konsolenbefehl an den Service senden — Body `{"command": "say hi"}` (Prozess **und** Docker; wird auditiert) |

## Schedules

Erfordert Permission `helix.panel.schedules`. Jobs führen eine beliebige Action
per Intervall (`everyMinutes`) oder täglich (`dailyAt` = `HH:mm`) aus.

**Scheduled Restarts:** Die Actions `service.restart <id> [delaySeconds]` und
`task.restart <task> [delaySeconds]` (Default 60 s) starten Services mit
Chat-Countdown neu — Announcements laufen als Broadcast in der Sprache jedes
Spielers (`helix.translations.network.restart.warn`/`.now`, Platzhalter
`{target}` und `{seconds}`; Marken 600/300/120/60/30/10/5/3/2/1 s vor dem
Neustart). Task-Restarts laufen rollierend (ein Service nach dem anderen);
ersetzt der Auto-Scaler den gestoppten Service bereits, wird kein zweiter
gestartet. Beispiel-Job für den täglichen 4-Uhr-Restart:
`{"id":"lobby-nightly", "action":"task.restart", "arguments":["Lobby","300"], "dailyAt":"04:00", "enabled":true}`.

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/schedules` | Alle Jobs |
| POST | `/schedules` | Job anlegen/ändern — Body `{id, action, arguments[], everyMinutes, dailyAt, enabled}` |
| DELETE | `/schedules/{id}` | Job löschen |
| POST | `/schedules/{id}/run` | Job sofort ausführen |

## Backups

Erfordert Permission `helix.panel.backups`. Backups sichern statische
Service-Workspaces als Zip (`Helix/backups/<serviceId>/`), Retention 10 pro
Service. Restore nur bei gestopptem Service. Per Scheduler automatisierbar
(Action `backup.create <serviceId>`).

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/backups` | Workspaces (mit running-Status) + alle Archive |
| POST | `/services/{id}/backups` | Backup erstellen → `201` mit BackupInfo |
| POST | `/backups/{serviceId}/{file}/restore` | Backup zurückspielen (Service muss gestoppt sein) |
| DELETE | `/backups/{serviceId}/{file}` | Archiv löschen |

## Files

Erfordert Permission `helix.panel.files`. Roots: `static:<serviceId>`,
`temp:<serviceId>`, `template:<name>`. Pfade sind auf den Root beschränkt
(Traversal → `400`); editierbar bis 1 MiB; Schreiben/Löschen wird auditiert.

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/files/roots` | verfügbare Roots |
| GET | `/files/list?root=&path=` | Verzeichnis-Listing (Ordner zuerst) |
| GET | `/files/content?root=&path=` | Textinhalt einer Datei |
| PUT | `/files/content` | Datei schreiben — Body `{root, path, content}` |
| DELETE | `/files?root=&path=` | Datei/Ordner (rekursiv) löschen |

## Players

Erfordert Permission `helix.panel.players`.

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/players` | Alle Online-Spieler (`name`, `uuid`, `proxyServiceId`, `joinedAtEpochMs`) |
| POST | `/players/{name}/message` | Nachricht senden — Body `{"value": "..."}` |
| POST | `/players/{name}/kick` | Kicken — Body `{"value": "reason"}` (Grund optional) |
| POST | `/players/{name}/ban` | Bannen (Bans-Addon) — Body `{"value": "reason", "duration": "7d"}` (`duration` leer = permanent) |

## Actions

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/actions` | alle registrierten Actions |
| POST | `/actions` | Action ausführen — Body: `{"action": "task.list", "arguments": []}` |

Jede CLI-Eingabe entspricht 1:1 einer Action; die REST-API kann damit
alles, was die Konsole kann.

## Addons

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/addons` | installierte Addons |
| POST | `/addons/{id}/enable` | Addon aktivieren |
| POST | `/addons/{id}/disable` | Addon deaktivieren |

## Intern (Bridges)

| Methode | Pfad | Beschreibung |
|---|---|---|
| POST | `/internal/heartbeat` | Bridge-Heartbeat `{serviceId, onlinePlayers, maxPlayers, tps?, memoryUsedMb?, memoryMaxMb?, cpuPercent?}` |
| GET | `/internal/routing?proxyServiceId=<id>` | Routing-Snapshot mit aufgelösten Backend-Adressen |
| POST | `/internal/join-check` | Join-Gate: `{name, uuid?}` → `{allowed, message?}` (wertet alle Addon-Gates aus) |
| GET | `/internal/commands?proxyServiceId=<id>` | Pending Proxy-Commands (z.B. Kicks), werden beim Abruf konsumiert |
| GET | `/internal/poll?proxyServiceId=<id>&routingVersion=<n>&commandCatalogVersion=<n>` | **Long-Poll**: kehrt sofort zurück, sobald Commands anstehen oder Routing/Command-Katalog sich ändern (instant push für Proxies) |
| POST | `/internal/permission-check` | Permission-Frage: `{name, permission, uuid?}` → `{allowed}` (Addon-Resolver überschreiben, sonst natives MC-System) |
| GET | `/internal/permission-nodes` | Liste der Permission-Nodes, die die Bridge beim Join nativ via `hasPermission` auswerten soll |
| POST | `/internal/player-event` | Join/Leave vom Proxy: `{type, name, uuid?, proxyServiceId, permissions?}` — `permissions` = nativ erteilte Nodes beim Join |
| GET | `/internal/players` | alle Online-Spieler des Netzwerks |
| GET | `/internal/player-commands` | Actions mit `playerCommand=true`, die Proxies als Commands registrieren |
| POST | `/internal/player-command` | Spieler-Command ausführen: `{player, command, arguments}` → ActionResult (Permission wird geprüft) |
| POST | `/internal/display` | Display-Profil eines Spielers: `{name}` → `{prefix, suffix, color}` |
| GET | `/internal/bridge-values` | von Addons publizierte globale Werte (Tablist, Chat-Format) |

## Beispiele

```bash
TOKEN=dev-token-change-me
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/platform/overview

curl -H "Authorization: Bearer $TOKEN" -X PUT \
  -H 'Content-Type: application/json' \
  -d '{"name":"Lobby","environment":"PAPER","version":"1.21.11",
       "minServiceCount":1,"maxServiceCount":3,
       "autoScale":{"enabled":true,"playerRatioThreshold":0.8,"idleStopSeconds":300}}' \
  http://127.0.0.1:8080/api/v1/tasks/Lobby

curl -H "Authorization: Bearer $TOKEN" -X POST \
  -H 'Content-Type: application/json' \
  -d '{"action":"service.list"}' \
  http://127.0.0.1:8080/api/v1/actions
```

Fehlerformat: `{"message": "..."}` mit passendem Statuscode
(`400` Validierung, `401` Auth, `404` unbekannt, `500` intern).
