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
token = "dev-token-change-me"  # Admin-Token: voller Zugriff, Bridges/Wrapper, Panel-Notlogin
loginPermission = "helix.panel.login"  # Permission für den Web-Panel-Login per MC-Account
codeTtlSeconds = 300      # Gültigkeit des ingame zugeschickten Login-Codes
sessionTtlSeconds = 86400 # Gültigkeit einer Web-Session
loginMessage = "§b§lHelix §r§7» §fYour panel login code is §b{code}§7."  # {code} wird ersetzt
tlsKeystore = ""          # Pfad zu einem PKCS12-Keystore → aktiviert HTTPS
tlsKeystorePassword = ""  # Passwort des Keystores/Privatschlüssels
tlsKeyAlias = "helix"     # Alias des Schlüssels im Keystore

[docker]
network = "helix"              # Docker-Netzwerk aller Helix-Container
image = "eclipse-temurin:24-jre"  # Basis-Image für Service-Container

[storage]
mode = "json"          # "json" (Dateien), "postgres" oder "mongodb"
url = "jdbc:postgresql://127.0.0.1:5432/helix"   # bzw. "mongodb://user:pass@host:27017"
user = "helix"         # postgres (bei mongodb bevorzugt in der URI)
password = "helix"
database = "helix"     # Datenbankname (mongodb)
poolSize = 8

[network]
name = "our network"   # Startwert des Anzeigenamens ({network}); danach im Panel editierbar

[audit]
retentionDays = 180    # harte Aufbewahrungsgrenze des Audit-Logs; 0 = unbegrenzt
```

Im `postgres`-Modus speichern **alle** Addons ihre Daten in der
geteilten Tabelle `addon_storage(addon_id, doc_key, value)` statt in
Dateien — praktisch fürs Netzwerk-weite/zentrale Speichern. Im
`mongodb`-Modus liegt dasselbe in der Collection `addon_storage` (Dokumente
mit zusammengesetztem `_id` `{a: addonId, k: docKey}`). In beiden zentralen
Modi wird zusätzlich der **Audit-Log** in der DB gespeichert
(`audit_log`-Tabelle bzw. -Collection); die Node öffnet dafür genau einen
gemeinsamen Pool/Client, der von Storage und Audit geteilt wird.

### Web-Panel-Login & Permissions

Der Panel-Login läuft über den Minecraft-Account: Name eingeben → Code
ingame erhalten → Code eingeben. Voraussetzung ist `helix.panel.login`.
Welche Views/Panels sichtbar sind, steuern Permissions:

- Views: `helix.panel.overview|tasks|services|proxy|events|logs|audit|addons|messages|settings`
- Addon-Seiten: `helix.panel.addon.<panelId>`
- Wildcard: `helix.panel.*` schaltet alles frei

Ohne Permission-Addon entscheidet das **native MC-System** (OP bzw.
`hasPermission`, z.B. via LuckPerms auf dem Proxy). Ist das Permission-Addon
aktiv, ist es allein maßgeblich. Das statische `control.token` bleibt als
Admin-/Notlogin mit vollem Zugriff gültig.

Härtung: `POST /auth/request-code` ist pro Spieler auf einen Code alle 30 s
begrenzt, Verify-Versuche pro Code auf 5. Für den Remote-Betrieb `tlsKeystore`
setzen (HTTPS) — ein PKCS12-Keystore lässt sich mit
`keytool -genkeypair -alias helix -keyalg RSA -storetype PKCS12 -keystore helix.p12`
erzeugen. `/internal/*` bleibt ausschließlich dem Admin-Token vorbehalten.

### Disconnect-Screens

Alle Trennungs-Screens sind im Web-Panel unter **Translations** editierbar —
**pro Sprache**, **mehrzeilig** und im **MiniMessage**-Format (Farbverläufe
`<gradient:…>`, Hex `<#rrggbb>`, `<bold>` …); Legacy-`&`-Codes werden
weiterhin unterstützt. Der Screen erscheint in der Sprache des Spielers
(`/helix language`, First Join = Client-Sprache). Verteilung:

- **Ban** (permanent/temporär) → `helix.translations.helix.bans.banned` / `….banned.temp`
- **Kick** → `helix.translations.helix.moderation.kick.screen`
- **Maintenance / Voll** → `helix.translations.velocity.screen.maintenance` / `….screen.server_full`

Universelle Placeholder (auf jedem Screen verfügbar — vom Proxy gefüllt):
`{player}`, `{network}`, `{server}`, `{online}`, `{max}`, `{date}`, `{time}`,
`{prefix}` (globaler Netzwerk-Prefix).

### Netzwerk-Name & globaler Prefix

Beides im Dashboard unter **Settings → Network** einstellbar (persistiert über
den zentralen Storage, Änderungen greifen sofort — der Name aus `node.toml`
ist nur der Startwert). Als `{network}` bzw. `{prefix}` verfügbar in
**jeder** konfigurierbaren Nachricht (alle Addon-Messages), in MOTD, Tablist
und Disconnect-Screens. Ausnahme: im Chat-Format bezeichnet `{prefix}`
weiterhin den Rang-Prefix des Spielers. Alle Texte werden einheitlich über
**MiniMessage** gerendert; Legacy-`&`-Codes bleiben überall unterstützt.
Domänen-Placeholder: Ban → `{reason}`, `{remaining}`, `{expiry}`, `{duration}`;
Kick → `{reason}`, `{moderator}`. Nicht belegte Placeholder bleiben leer/roh.

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
