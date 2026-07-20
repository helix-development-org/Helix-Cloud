# REST-API-Referenz

Basis-URL: `http://<host>:<port>/api/v1` (Default `127.0.0.1:8080`).
Alle Endpunkte erfordern `Authorization: Bearer <token>` aus
`config/node.toml`; ohne Token antwortet die API mit `401`.

Das Dashboard unter `/` ist statisch und nutzt dieselbe API.

## Platform

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/platform/overview` | Aggregierte Zähler (Services, Player, Version) |
| GET | `/logs?tail=300` | Node-Log (Ringpuffer über stdout/stderr-Capture) |
| GET | `/events?limit=200` | Event-Timeline (neueste zuerst): Service-Lifecycle, Player, Moderation, Proxy, Tasks |
| GET | `/audit?limit=300&category=<cat>` | Vollständiger Audit-Log: jeder HTTP-Request, jede Action, Auth-Versuche, Lifecycle (persistiert in `audit.jsonl` bzw. bei `storage.mode = "postgres"` in Tabelle `audit_log`) |
| GET | `/proxy` | Proxy-Übersicht: Maintenance, Proxies, Backend-Routing |
| POST | `/proxy/maintenance` | Maintenance schalten — Body `{"enabled": true}` |
| GET | `/panels` / `/panels/{id}` | Addon-Dashboard-Seiten (Metadaten / HTML) |
| GET | `/messages` | Alle konfigurierbaren Addon-Nachrichten (addonId → key → template) |
| POST | `/messages` | Nachricht setzen/zurücksetzen — Body `{addonId, key, value}` oder `{addonId, key, reset:true}` |

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
| POST | `/internal/heartbeat` | Bridge-Heartbeat `{serviceId, onlinePlayers, maxPlayers, tps?}` |
| GET | `/internal/routing?proxyServiceId=<id>` | Routing-Snapshot mit aufgelösten Backend-Adressen |
| POST | `/internal/join-check` | Join-Gate: `{name, uuid?}` → `{allowed, message?}` (wertet alle Addon-Gates aus) |
| GET | `/internal/commands?proxyServiceId=<id>` | Pending Proxy-Commands (z.B. Kicks), werden beim Abruf konsumiert |
| GET | `/internal/poll?proxyServiceId=<id>&routingVersion=<n>&commandCatalogVersion=<n>` | **Long-Poll**: kehrt sofort zurück, sobald Commands anstehen oder Routing/Command-Katalog sich ändern (instant push für Proxies) |
| POST | `/internal/permission-check` | Permission-Frage: `{name, permission, uuid?}` → `{allowed}` (erteilt, sobald ein Resolver erteilt) |
| POST | `/internal/player-event` | Join/Leave vom Proxy: `{type, name, uuid?, proxyServiceId}` |
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
