# Helix-Cloud

Helix-Cloud ist ein schlankes Orchestrierungssystem für Minecraft-Netzwerke
nach CloudNet-Vorbild: eine Node verwaltet **Tasks** (Blueprints) und startet
daraus **Services** (Paper-Server, Velocity-Proxies) — als lokaler Prozess
oder Docker-Container. Server-Typ und Version sind Konfiguration, kein Code.

## Features

- **Eine `Launcher.jar`** — einziger Startpunkt, bündelt Node, Wrapper und
  beide Bridges; legt beim ersten Start `Helix/` mit aller Config an
- **Persistente & temporäre Server** — statische Services behalten ihren
  Workspace, dynamische starten frisch und räumen sich nach dem Stop weg
- **Auto-Scaling** — Mindestanzahl wird immer gehalten (inkl. Auto-Restart),
  bei hoher Slot-Auslastung wird hochskaliert, leerer Überschuss stoppt
- **Paper + Velocity** — universeller Wrapper, dynamische Backend-Registrierung
  am Proxy, Fallback/Lobby-Wahl, Maintenance-Modus
- **Actions** — ein Contract für CLI, REST, Dashboard und Addons
- **Web-Dashboard** — Token-Login, Live-Übersicht, Task-/Service-/Addon-
  Verwaltung, Log-Viewer, Action-Konsole (hell/dunkel, ohne externe Assets)
- **Addons** — `.hxa` Pakete mit Classloader-Isolation und Lifecycle

## Quickstart

```bash
./gradlew :helix-node:jar
java -jar helix-node/build/libs/Launcher.jar
```

Dashboard: `http://127.0.0.1:8080/` — Token: `dev-token-change-me`
(ändern in `Helix/config/node.toml`).

## Server-Installation (Linux)

```bash
curl -fsSL https://raw.githubusercontent.com/helix-development-org/Helix-Cloud/main/tools/install.sh | sudo bash
```

Installiert nach `/opt/helix`: Launcher.jar (Release-Asset oder
Source-Build), passende Java-Runtime (Temurin, falls das System-Java zu
alt ist), Config mit **zufälligem Admin-Token** und eine systemd-Unit, die
mit dem In-Place-Restart kompatibel ist (`/helix backend restart` lässt
die Services headless weiterlaufen; systemd startet die Node neu und
adoptiert sie). Optionen: `--dir`, `--user`, `--host`, `--port`, `--jar`,
`--ref`, `--no-systemd`, `--no-start` — Details: `tools/install.sh --help`.

```bash
systemctl status helix        # Verwaltung
journalctl -u helix -f        # Logs
grep token /opt/helix/Helix/config/node.toml   # Panel-Token
```

```text
helix> task.create Lobby paper 1.21.11 min=1 max=3 autoscale=true
helix> task.create Proxy velocity 3.4.0
helix> service.list
```

## Verifikation

```bash
./gradlew check          # Tests + 100% KDoc-Gate
./tools/e2e-smoke.sh     # End-to-End: Boot → Task → Service → Heartbeat → Shutdown
./gradlew releaseBundle  # Release-Artefakte mit Checksummen
```

## Dokumentation

- [Architektur](docs/ARCHITECTURE.md) — Begriffe, Module, Datenflüsse
- [Konfiguration](docs/CONFIG.md) — node.toml, versions.toml, Task-Referenz
- [REST-API](docs/REST.md) — Control-API-Endpunkte
- [Addons](docs/ADDONS.md) — eigene Addons entwickeln

## Lizenz & Urheberschaft

Helix-Cloud ist Open Source unter der **Apache License 2.0** — siehe
[LICENSE](LICENSE). Copyright © 2026 **Helix-Development**.

Das bedeutet: Nutzung, Änderung und Weitergabe sind erlaubt, aber die
[NOTICE](NOTICE)-Datei und der Copyright-Hinweis **müssen erhalten bleiben**,
Änderungen müssen als solche gekennzeichnet werden, und der Name
„Helix-Cloud" darf **nicht** für abgeleitete Projekte verwendet werden
(Apache 2.0, §6). Forks sind willkommen — unter eigenem Namen und mit
Verweis auf dieses Projekt als Ursprung.
