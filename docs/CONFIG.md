# Konfigurations-Referenz

Alle Konfiguration liegt unterhalb des `Helix/` Datenverzeichnisses, das die
`Launcher.jar` beim ersten Start neben sich anlegt.

## Verzeichnislayout

```text
Helix/
  config/
    node.toml        # Control-API, Token, Docker
    versions.toml    # verfügbare Plattform-Versionen
  tasks/             # ein <name>.toml pro Task
  templates/         # Vorlagen, werden in Service-Workspaces kopiert
  services/
    static/          # persistente Workspaces statischer Services
    temp/            # Wegwerf-Workspaces dynamischer Services
  cache/             # heruntergeladene Server-Jars
  addons/            # installierte .hxa Addons
```

## `config/node.toml`

```toml
[control]
host = "127.0.0.1"        # Bind-Interface der Control-API + Dashboard
port = 8080
token = "dev-token-change-me"  # Bearer-Token für API, Dashboard und Bridges

[docker]
network = "helix"              # Docker-Netzwerk aller Helix-Container
image = "eclipse-temurin:24-jre"  # Basis-Image für Service-Container

[storage]
mode = "json"          # "json" (Dateien) oder "postgres"
url = "jdbc:postgresql://127.0.0.1:5432/helix"
user = "helix"
password = "helix"
poolSize = 8
```

Im `postgres`-Modus speichern **alle** Addons ihre Daten in der
geteilten Tabelle `addon_storage(addon_id, doc_key, value)` statt in
Dateien — praktisch fürs Netzwerk-weite/zentrale Speichern.

## `config/versions.toml`

Server-Typ und Version sind Konfiguration, kein Code. Jeder Eintrag ist über
die PaperMC Fill-API auflösbar; `url` überschreibt den Download direkt.

```toml
[[paper]]
version = "1.21.11"

[[velocity]]
version = "3.4.0"
# url = "https://example.org/custom-velocity.jar"  # optionaler Override
```

## Task-Format (`tasks/<name>.toml`)

| Key | Default | Bedeutung |
|---|---|---|
| `name` | – | eindeutiger Task-Name, Prefix der Service-Ids |
| `environment` | – | `PAPER` oder `VELOCITY` |
| `version` | – | Plattform-Version aus `versions.toml` |
| `executor` | `PROCESS` | `PROCESS` oder `DOCKER` |
| `staticServices` | `false` | `true` = persistente Services, Workspace bleibt erhalten |
| `minServiceCount` | `1` | wird von der Node immer am Leben gehalten |
| `maxServiceCount` | `1` | Obergrenze für Auto-Scaling und manuelle Starts |
| `memoryMb` | `1024` | JVM-Heap pro Service |
| `maxPlayers` | `100` | Slots pro Service |
| `startPort` | `25565` | erster Port bei der Port-Vergabe |
| `jvmArgs` | `[]` | zusätzliche JVM-Argumente |
| `templates` | `["default"]` | Ordner unter `templates/`, in den Workspace kopiert |
| `fallbackEligible` | `false` | Backend darf Proxy-Fallback/Lobby sein |
| `maintenance` | `false` | Services dieses Tasks lehnen Joins ab |
| `disabledAddons` | `[]` | Addon-Ids, die für diesen Task **aus** sind; alle anderen sind aktiv |

```toml
[autoScale]
enabled = false
playerRatioThreshold = 0.8   # Slot-Auslastung, ab der hochskaliert wird
idleStopSeconds = 300        # so lange leer, bevor Überschuss stoppt
```

### Statisch vs. dynamisch

- **dynamisch** (`staticServices = false`): jeder Start bekommt einen
  frischen Workspace unter `services/temp/`, der nach dem Stop gelöscht
  wird. Für Lobbys, Game-Server, alles Skalierbare.
- **statisch** (`staticServices = true`): der Workspace unter
  `services/static/<id>` bleibt über Neustarts erhalten (Welten, Configs).
  Node-eigene Dateien (`Wrapper.jar`, Bridge, `server.jar`,
  `wrapper.properties`) werden trotzdem bei jedem Start aktualisiert.

## Docker-Execution

Voraussetzungen: `docker` CLI auf der Node (Podman mit Docker-Emulation
funktioniert ebenfalls). Container laufen im konfigurierten Netzwerk,
mounten den Workspace nach `/helix` (mit `:z` SELinux-Label, No-Op auf
Nicht-SELinux-Systemen) und starten denselben Wrapper wie die
Prozess-Execution. Die Node ist aus Containern über
`host.docker.internal` erreichbar (per `--add-host … host-gateway`).

**Wichtig:** Für Docker-Services muss die Control-API auf allen
Interfaces lauschen, sonst erreichen die Bridges die Node nicht:

```toml
[control]
host = "0.0.0.0"   # statt 127.0.0.1, wenn Docker-Executor genutzt wird
```

Crasht ein Service direkt nach dem Start, pausiert der Auto-Scaler den
Task für 60 Sekunden; die letzten Container-Logs bleiben über
`service.logs <id>` und im Dashboard abrufbar (FAILED-Services bleiben
sichtbar, bis der nächste Start ihre Id wiederverwendet).

Adress-Matrix Proxy → Backend siehe [ARCHITECTURE.md](ARCHITECTURE.md).

## Bridge-Umgebungsvariablen

Der Wrapper exportiert an jeden Service:

| Variable | Inhalt |
|---|---|
| `HELIX_SERVICE_ID` | Service-Id, z.B. `Lobby-1` |
| `HELIX_TASK` | Task-Name |
| `HELIX_SERVICE_PORT` | zugewiesener Port |
| `HELIX_CONTROL_URL` | Basis-URL der Control-API |
| `HELIX_CONTROL_TOKEN` | Bearer-Token |
