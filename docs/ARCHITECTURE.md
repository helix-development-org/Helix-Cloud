# Architektur

Stand: 2026-07-19

## Zielbild

Helix-Cloud ist der schlanke Nachfolger des Helix-Projekts. Statt einem
kompilierten Modul pro Server-Software und Version gibt es ein generisches
Task/Service-Modell nach CloudNet-Vorbild: **Server-Typ und Version sind
Konfiguration, kein Code.**

Es gibt genau **eine `Launcher.jar`** als Startpunkt. Sie enthält die Node,
den Wrapper und beide Bridges als eingebettete Ressourcen.

## Kernbegriffe

| Begriff | Bedeutung |
|---|---|
| **Node** | Der eine Controller-Prozess. Hält alle Registries, die Control-API, die CLI und den Addon-Loader. Kein Cluster in v1. |
| **Task** | Blueprint für Services: Environment (`PAPER` \| `VELOCITY`), Version, minimale Service-Anzahl, statisch/dynamisch, Speicher, Templates. Liegt als Config unter `Helix/tasks/<name>.toml`. |
| **Service** | Laufende Instanz eines Tasks. Lifecycle: `PREPARED → STARTING → RUNNING → STOPPING → STOPPED`. |
| **Wrapper** | Universeller Starter. Startet **jede** Server-Jar, injiziert die passende Bridge und verbindet den Service mit der Node. Ein Wrapper für alle Environments. |
| **Bridge** | Ein Plugin pro Plattform-Typ (`bridge-paper`, `bridge-velocity`). Heartbeats, Routing, Fallback/Lobby, Player-Actions. Keine versionsspezifischen Bridges. |
| **Action** | Der generische Interaktions-Contract. CLI, REST, Bridges und Addons lösen einheitlich Actions aus. |
| **Addon** | Erweiterung im HXA-Format (`addon.json` + `addon.jar`), wird in die Node geladen und registriert eigene Actions/Listener. |

## Module

| Modul | Zweck |
|---|---|
| `helix-api` | Öffentliche Contracts und DTOs für alle anderen Module. |
| `helix-node` | Controller: Task-/Service-Registry, Service-Lifecycle, Version-Downloads, Control-API, CLI, Addon-Loader. Produziert die `Launcher.jar` (Fat-Jar mit eingebettetem Wrapper und Bridges). |
| `helix-wrapper` | Universeller Service-Wrapper. |
| `helix-bridge-paper` | Paper-Plugin (Heartbeat, Commands, Join-Gate). |
| `helix-bridge-velocity` | Velocity-Plugin (Backend-Registrierung, Routing, Fallback). |
| `helix-addon-sdk` | Addon-Basisklassen und -API. |
| `helix-addon-example` | Referenz-Addon, beweist den Addon-Contract. |

## Service-Execution

Zwei Execution-Backends von Anfang an, pro Task wählbar:

- **process** — Service läuft als lokaler Kindprozess der Node.
- **docker** — Service läuft als Container im Helix-Docker-Netzwerk.

Die Adress-Auflösung zwischen Proxy und Backends hängt von beiden
Execution-Backends ab:

| Proxy | Backend | Adresse |
|---|---|---|
| Docker | Docker | `containerName:containerPort` |
| Docker | Process | `host.docker.internal:port` |
| Process | Docker | `127.0.0.1:hostPort` |
| Process | Process | `127.0.0.1:port` |

## Versionen und Downloads

Keine Runtime-Module pro Version. Eine `versions.toml` mappt
`environment + version → Download-Quelle` (PaperMC-API für Paper und
Velocity). Neue Versionen = ein Config-Eintrag, kein Release.

## Verzeichnisstruktur zur Laufzeit

```text
Helix/
  config/
    node.toml        # Control-API, Auth-Token, Docker-Einstellungen
    versions.toml    # Environment + Version → Download
  tasks/
    Lobby.toml
    Proxy.toml
  templates/
    Lobby/           # Dateien, die in jeden Lobby-Service kopiert werden
  services/
    static/          # persistente Workspaces (staticServices = true)
    temp/            # Wegwerf-Workspaces, nach Stop gelöscht
  cache/             # heruntergeladene Server-Jars
  addons/
    helix-example.hxa
```

Jeder Service-Workspace enthält `Wrapper.jar`, `wrapper.properties`,
`server.jar`, die injizierte Bridge unter `plugins/` und die generierte
Plattform-Config (`server.properties`/`velocity.toml`). Details in
[CONFIG.md](CONFIG.md).

## Datenflüsse

1. `Launcher.jar` startet → legt `Helix/` an → bootet die Node
   (Control-API + Dashboard, Auto-Scaler, Addons, CLI).
2. Der Auto-Scaler hält pro Task `minServiceCount` Services am Leben,
   skaliert bei Slot-Auslastung hoch und stoppt leeren Überschuss.
3. Der Wrapper startet die Server-Jar im vorbereiteten Workspace; die
   Bridge liest ihre Verbindung aus `HELIX_*`-Umgebungsvariablen.
4. Bridges senden Heartbeats (erster Heartbeat ⇒ `RUNNING`); die
   Velocity-Bridge zieht Routing-Snapshots und registriert Backends
   dynamisch, wählt Initial-Server/Fallback und erzwingt Maintenance.
5. Actions laufen einheitlich über CLI, REST, Dashboard und Addons.

## Weitere Referenzen

- [CONFIG.md](CONFIG.md) — alle Konfigurationsdateien und Task-Keys
- [REST.md](REST.md) — Control-API-Endpunkte
- [ADDONS.md](ADDONS.md) — Addons entwickeln und paketieren
