# Changelog

## 0.77.3 — 2026-07-28

### Fix: `/profilemenu`-Titel ohne Resource-Pack-Font (leeres/kaputtes Menü)
- **Der Menütitel nutzt IGuis `centeredText`, das auf resource-pack-eigene
  Fonts angewiesen ist** (unsichtbare Abstands-Glyphen + zeilenweise
  Vanilla-Ascii-Fonts, siehe `DisplayBuilder`/`SpacingRenderer`) — anders
  als Guard und BetterMsgs hatte `helix-addon-profile-paper` dafür nie
  einen eigenen Pack-Generator, `IGui.install` lief also mit dem
  Default-Namespace `minecraft`, für den nirgends passende Font-Dateien
  existierten. Ergebnis: das Menü öffnete sich ohne Fehler, aber der Titel
  (und die unsichtbaren Zentrierungs-Glyphen) rendern ohne passende Fonts
  kaputt/leer.
- **Neuer `PackGenerator`** (analog zu Guard/BetterMsgs) erzeugt
  `assets/helix_profile/font/spaces.json` + `text_row_0..6.json`;
  `ProfileGuiService` konfiguriert `fonts = GuiFontConfiguration(namespace
  = "helix_profile")`. Das Profile-HXA bündelt jetzt ebenfalls ein
  `pack.zip`.

## 0.77.2 — 2026-07-28

### Fix: NPC und BetterMsgs hatten denselben per-Service-Token-403 wie Guard/Profile
- **`NodeClient` in `helix-addon-npc-paper` und `helix-addon-bettermsgs-paper`
  sprachen ebenfalls `POST /api/v1/actions` mit dem per-Service-Token an** —
  demselben architektonischen Problem wie Guard/Profile (0.77.0/0.77.1)
  zum Opfer gefallen, nur noch nicht gemeldet. Betroffen: `npc.save/delete/
  list/get` und `bettermsgs.send/history/contacts/focus`.
- Beide `NodeClient`s sprechen jetzt `/api/v1/internal/action` an; die
  jeweiligen Actions sind als `bridgeInvocable` markiert.

## 0.77.1 — 2026-07-28

### Fix: `/internal/action` fehlte der `/api/v1`-Prefix (HTTP 404)
- **0.77.0s neue Bridge-Route wurde von `ProfileNodeClient`/`HelixNodeStore`
  als `<url>/internal/action` statt `<url>/api/v1/internal/action`
  angesprochen** — alle anderen `/internal/*`-Routen hängen ebenfalls unter
  `/api/v1`, dieser Prefix fehlte beim Umstellen von `/api/v1/actions`.
  Führte zu `HTTP 404` statt der beabsichtigten `HTTP 200`.

## 0.77.0 — 2026-07-28

### Fix: per-Service-Tokens konnten keine eigenen Actions aufrufen (HTTP 403)
- **`profile.texture.list` (und jede andere `profile.texture.*`/`profile.setting.*`-Action)
  schlug mit `HTTP 403` fehl**, sobald `ProfileNodeClient` sie über
  `POST /api/v1/actions` aufrief: diese Route akzeptiert für Actions ohne
  deklarierte Permission ausschließlich das statische Admin-Token oder eine
  `helix.admin`-Session — ein per-Service-Token (`HELIX_CONTROL_TOKEN`) erfüllt
  keins von beidem und wurde immer abgelehnt. Betraf ebenso Guards
  `HelixNodeStore` (`guard.store.*`/`guard.query.*`), die denselben Endpunkt
  mit demselben Tokentyp ansprach.
- **Neue `POST /internal/action`**: Bridge-Route für per-Service-Token-Aufrufe,
  erreicht nur Actions mit dem neuen `ActionDescriptor`-Flag
  `bridgeInvocable = true` — ein bewusstes Opt-in pro Action (analog zu
  `playerCommand`), damit ein kompromittierter Spielserver nicht plötzlich
  jede beliebige Node-Action ausführen kann.
- `ProfileNodeClient` und `HelixNodeStore` (Guard) sprechen jetzt
  `/internal/action` statt `/api/v1/actions` an; die jeweils genutzten
  Actions (`profile.view`, `profile.setting.set`, `profile.texture.*`,
  `guard.store.*`, `guard.query.*`) sind als `bridgeInvocable` markiert.

## 0.76.0 — 2026-07-28

### Fix: IGui-Textur-Datenbank ohne direkte DB-Verbindung
- **`/profilemenu` warf beim Start eine `IllegalStateException`**
  („Configure PostgreSQL in IGui.install with postgres(...)"), weil die
  IGui-Konfiguration ohne Textur-Datenbank initialisiert wurde.
- **Neue `NodeGuiTextureDatabase`**: das Paper-Menü spricht jetzt über
  neue `profile.texture.list/get/put/remove`-Actions mit dem Profile-Addon
  statt eine eigene (Postgres-)Datenbankverbindung vom Spielserver aus
  aufzumachen — konsistent mit der Storage-Anbindung jedes anderen
  Addons in diesem Projekt (z. B. Guard: `storage.mode: helix` →
  `guard.store.*`-Actions → Node-Storage-Provider).

## 0.75.0 — 2026-07-28

### Neuer Release-Workflow
- **Bei jedem `vX.Y.Z`-Tag baut, testet und packt eine neue GitHub-Actions
  Pipeline (`release.yml`) das Release-Bundle** (Launcher.jar + alle
  `.hxa`-Addon-Dateien + SHA-256SUMS) und veröffentlicht es als GitHub
  Release mit diesen Dateien als Downloads.
- **Die Release-Beschreibung ist der passende CHANGELOG.md-Abschnitt**
  für diese Version, nicht nur eine generische Commit-Liste — fällt auf
  GitHubs automatisch generierte Notizen zurück, falls ein Tag (z. B. ein
  sehr alter) keinen passenden Changelog-Eintrag hat.

## 0.74.0 — 2026-07-28

Neues `helix-addon-cosmetics`: anziehbare Flügel und Kopfbedeckungen,
letzte Etappe des Cosmetic-/Subtitle-/Profile-Features.

### Sechs Flügel, sechs Kopfbedeckungen
- **Gewählt über das Profile-System** als zwei Choice-Settings
  (`wings`/`headwear`), rang-/permission-gated pro Eintrag — genau wie
  bei Subtitles hält dieses Addon keinen eigenen Wertespeicher, sondern
  liest/schreibt ausschließlich über `profile.setting.get`.
- **Echte, handgeschriebene 3D-Item-Modelle statt Textur-Tricks**: jedes
  Cosmetic ist ein `CustomModelData`-getaggter Carrier mit eigener
  Geometrie (Flügel: zwei flache Panels; Kronen: Band + vier
  Ecken-Zacken; Halos: flacher quadratischer Ring) und einer zur
  Build-Zeit mit Java2D gezeichneten Textur — die exakt gleiche Technik,
  die BetterMSGs schon für seine GUI-Texturen nutzt, hier aber für echte
  Item-Modelle statt Font-Glyphen.
- **Kein Rüstungs-/Elytra-Slot wird belegt**: eine Item-Display-Entity
  pro Slot, jeden Tick neu positioniert und nach Blickrichtung rotiert —
  echte Rüstung und ein echtes Elytra funktionieren unabhängig davon
  weiter.

### Bekannte Grenzen
- Die Modell-Proportionen/Positionierung sind ohne Live-Server-Test nicht
  pixelgenau verifiziert — sinnvoller erster Wurf, aber ggf. noch
  visuelles Fine-Tuning nötig.
- Ältere, per ViaVersion angebundene Clients: `CustomModelData`-Overrides
  sind breit kompatibel, aber ebenfalls nicht gegen einen echten alten
  Client getestet.

## 0.73.0 — 2026-07-28

Neues `helix-addon-subtitles`: eine zweite Anzeigezeile unter dem
Spielernamen, auswählbar über das Profile-System.

### Vordefiniert + eigener Text
- **`subtitle.config.add/remove/list`**: Betreiber pflegen eine Liste
  wählbarer Subtitles (Text + optional ein Permission-Node pro Eintrag).
- **Ein permission-gated Freitext** (`helix.subtitle.custom`) überschreibt
  die Listenauswahl, wenn gesetzt.
- Beide Optionen laufen über das neue Profile-System (`/profile` oder
  `/profilemenu`) — dieses Addon hält keinen eigenen Wertespeicher,
  sondern liest/schreibt ausschließlich über `profile.setting.get`.

### Rendering ohne Passagier-Trick
- **Paper-seitig rendert eine dem Spieler folgende Text-Display-Entity**
  knapp unter dem Namensschild, jeden Tick neu positioniert statt als
  Passagier angehängt — stört dadurch nicht, wenn der Spieler tatsächlich
  ein Reittier nutzt.

## 0.72.0 — 2026-07-28

Neues `helix-addon-profile`: das zentrale Spielerprofil, Grundlage für die
kommenden Cosmetic-/Subtitle-Features.

### Ein Profil, beliebig viele beitragende Addons
- **Neuer Erweiterungspunkt**: jedes Addon kann über
  `AddonContext.registerProfileInfoProvider` (read-only, z. B. Statistiken)
  oder `registerProfileSettingProvider` (interaktiv: Toggle/Choice/Freitext)
  Einträge im Profil eines Spielers beisteuern — ohne die anderen Addons
  zu kennen. Der Node aggregiert, das Profile-Addon zeigt/verwaltet.
- **Das Profile-Addon speichert den tatsächlich gewählten Wert zentral**
  (`profile.setting.set`, mit Gating pro Option; `profile.setting.clear`
  setzt zurück). Ein `validate`-Hook lässt beitragende Addons Prüfungen
  durchsetzen, die das Typsystem allein nicht abbildet (z. B. ein
  Freitext-Name, der mit einem bekannten Account kollidiert).
- **`profile.setting.admin-set`**: Staff kann über das Dashboard jede
  Einstellung überschreiben, auch rang-gated Optionen, die für den
  Spieler selbst gerade gesperrt sind.

### Zwei Wege zum Profil
- **`/profile`**: netzwerkweiter Text-Fallback (funktioniert überall,
  auch von der Konsole) — zeigt Statistiken und Einstellungen als Text,
  `/profile set <key> <wert>` ändert eine Einstellung.
- **`/profilemenu`**: grafisches, IGui-basiertes Menü auf Paper-Servern.
  Bewusst ein eigener Befehlsname — Velocity registriert `/profile` als
  Proxy-Befehl und würde ein gleichnamiges Paper-Plugin-Kommando nie zum
  Zug kommen lassen.
- **Dashboard-Panel „Profiles"**: Staff kann ein Spielerprofil ansehen
  und Einstellungen manuell setzen oder zurücksetzen.

## 0.71.0 — 2026-07-28

Vorarbeit für das kommende Cosmetic-/Subtitle-/Profile-Feature.

### IGui ins Monorepo geholt
- **Die bisher als Sibling-Composite-Build eingebundene IGui-Library
  (Resource-Pack-Font-GUIs) ist jetzt ein reguläres Modul** (`helix-gui`)
  direkt im Repo, statt einen separaten `../IGui`-Checkout vorauszusetzen.
  `helix-addon-guard-paper` und `helix-addon-bettermsgs-paper` hängen jetzt
  von `project(":helix-gui")` statt vom externen Artefakt ab.
- Die CI-Pipeline braucht dadurch keinen zweiten Checkout mehr; der
  Gradle-Version-Catalog verliert den nun ungenutzten `igui`-Eintrag.
- Alle öffentlichen Deklarationen der Library sind jetzt KDoc-dokumentiert
  (vorher ohne die hiesige KDoc-Pflicht entwickelt).

## 0.70.0 — 2026-07-28

Fünfte und letzte Etappe von Phase 2: Ops & Release-Governance.

### CI + Governance-Dokumente
- **Neue GitHub-Actions-Pipeline** (`./gradlew build` +
  `verifyKDocAvailability` bei jedem Push/PR).
- **Neue `SECURITY.md`** (Pre-1.0/Alpha-Status, Melde-Weg für
  Sicherheitslücken) und **`CONTRIBUTING.md`** (Modul-Übersicht,
  Build/Test-Befehle, KDoc-Gate).
- **Org-Referenz korrigiert**: Das Projekt liegt jetzt unter der
  Organisation `helix-development-org` statt im privaten Account —
  `tools/install.sh`, die README-Installationszeile und der lokale
  `origin`-Remote zeigen konsistent auf `helix-development-org/Helix-Cloud`.

### Gradle Version Catalog
- **Alle bisher pro Modul verstreuten Abhängigkeitsversionen** sind jetzt
  in `gradle/libs.versions.toml` zentralisiert; dabei eine echte
  Versions-Divergenz gefunden und behoben (`kotlinx-serialization-json`
  war in einem Addon eine Minor-Version voraus).

### Doku
- **Kurze englische Zusammenfassung** oben in der README für
  englischsprachige Leser, der Rest bleibt vorerst deutsch.

## 0.69.0 — 2026-07-28

Vierte Etappe von Phase 2, komplett neuer Scope: eine eigene
Gameplay-Kernschicht statt server-spezifischer Plugins.

### Neue Addons
- **`helix-addon-stats`**: generische Stats/Leaderboards-API (beliebiger
  String-Key als Statistik-Name) plus saisonale Resets, die den bisherigen
  Stand archivieren statt ihn zu löschen.
- **`helix-addon-parties`**: schlanke, netzwerkweite, bewusst nicht
  über einen Node-Neustart hinweg persistierte Gruppen unterhalb des
  Clan-Systems (Anführer, Einladen/Annehmen/Verlassen/Kick,
  Führungswechsel beim Verlassen des Anführers).
- **`helix-addon-maprotation`**: node-koordinierte Karten-Rotation
  (zeit- oder rundenbasiert über den bestehenden Scheduler), beschränkt
  auf die Entscheidung/Ankündigung — das eigentliche Laden der Welt bleibt
  Aufgabe der Server-eigenen Plugins.

## 0.68.0 — 2026-07-28

Dritte Etappe von Phase 2: Ökosystem & Compliance.

### Echter Bukkit-Permission-Provider
- **Das eigene Permissions-System wird jetzt als echter Bukkit-Permission-
  Provider registriert** (`HelixPermissionProvider`), sodass
  Drittanbieter-Plugins, die `Player#hasPermission(...)` aufrufen,
  transparent die Netzwerk-Entscheidung sehen — kein LuckPerms nötig.

### Whitelist
- **Neue netzwerkweite, betreiberkonfigurierbare Whitelist** (`whitelist
  mode|add|remove|list`), unabhängig vom bestehenden Wartungsmodus.

### DSGVO-Export/-Löschung + Audit-Aufbewahrung
- **Neue `player.gdpr-export`/`player.gdpr-delete`-Aktionen**, die alle
  personenbezogenen Daten eines Spielers über Bans, Rechte, Freunde, Clan,
  Wirtschaft und Verwarnungen hinweg aggregieren bzw. entfernen (ein
  Clan-Owner wird dabei nie automatisch gelöscht — Übergabe/Auflösung
  zuerst).
- **Das Audit-Log erzwingt jetzt eine konfigurierbare harte
  Aufbewahrungsfrist** (`[audit].retentionDays`, Standard 180 Tage),
  stündlich durchgesetzt — vorher wuchs es unbegrenzt.

### Staff-Spieler-Lookup
- **Neue Dashboard-Ansicht**: Staff kann einen Spieler per Name/UUID
  suchen und sieht Online-Status plus die aggregierten Daten jedes
  installierten Addons an einem Ort.

### Vorbereitet, aber noch nicht abgeschlossen
- **Vault-Economy-Provider und PlaceholderAPI-Expansion**: ein
  wiederverwendbarer Platzhalter-Resolver ist fertig und getestet, die
  eigentliche Anbindung an die beiden externen APIs konnte in dieser
  Umgebung nicht abgeschlossen werden (kein Netzwerkzugriff auf die
  benötigten Artefakt-Repositories). Folgt, sobald verfügbar.

## 0.67.0 — 2026-07-28

Zweite Etappe von Phase 2: Moderations- und Anticheat-Baseline.

### Mute + Chat-Filter
- **Neues `/mute`, `/unmute`, `/mutes`**: zeitlich befristete oder
  permanente Stummschaltungen, serverseitig direkt im Chat-Pfad der Bridge
  durchgesetzt (kein Zusatz-Roundtrip pro Nachricht).
- **Neuer konfigurierbarer Chat-Wortfilter** (`/blocklist add|remove|list`).

### Bans: Verhängender + Historie
- **Bans tragen jetzt fest, wer sie verhängt hat** (`issuedBy`), threaded
  durch `/bans set`, `/tempban` und die REST-Ban-Route.
- **Aufgehobene oder abgelaufene Bans werden nicht mehr gelöscht**,
  sondern in eine begrenzte Historie verschoben (`/bans history <player>`).

### Guard-Fixes
- **Offline-Unban-Bug behoben**: Ein Unban für einen gerade nicht
  erreichbaren Spieler ging bisher verloren, wenn der Fire-and-Forget-Weg
  fehlschlug — jetzt wird zuerst der garantierte synchrone Weg versucht.
- **Aufbewahrungsfristen für Verstöße und Replays werden jetzt tatsächlich
  durchgesetzt** (waren zuvor nur ungenutzte Konfigurationswerte).
- **Neue Elytra-Flug-Erkennung**, konservativ kalibriert nach demselben
  False-Positive-Anspruch wie die bestehenden Bewegungs-Checks.
- **Physik-Profile erweitert von nur 1.21 auf 1.9–1.21.11** (via
  ViaVersion angebundene ältere Clients werden jetzt korrekt geprüft statt
  ungeprüft durchgelassen).

### Weitere Härtung
- **`/modlookup <player>`**: Aggregierte Ban-/Mute-/Warn-/Incident-Ansicht
  für Staff an einem Ort.
- **Verwarnungen laufen jetzt nach einer konfigurierbaren Frist ab**
  (Standard 30 Tage), statt für immer in die Eskalations-Zählung
  einzufließen.
- **Freundschaftsanfragen haben jetzt eine Cooldown-Sperre** gegen
  Spam/Belästigung durch wiederholte Anfragen.

## 0.66.0 — 2026-07-28

Erste Etappe von Phase 2 (Content & Ökosystem): Korrektheits-Fixes im
Gameplay-Bereich.

### Clan-Bank: kein Münz-Dupe/-Verlust mehr
- **`/clan bank withdraw` zahlt jetzt erst aus, nachdem die Bank tatsächlich
  belastet wurde**, statt umgekehrt. Vorher konnte ein fehlgeschlagener oder
  durch ein Wettrennen zweier gleichzeitiger Abhebungen ungültig gewordener
  Bank-Abzug trotzdem echte Münzen an den Spieler auszahlen — ein
  Dupe-Bug.
- **`/clan bank deposit` erstattet die Münzen jetzt zurück**, wenn die
  Gutschrift auf der Bank fehlschlägt (z. B. weil der Clan zwischenzeitlich
  aufgelöst wurde), statt sie stillschweigend zu vernichten.

### Nick: Impersonation-Schutz erweitert
- **Ein Nick darf jetzt auch nicht mehr den Namen eines bekannten,
  aktuell offline Premium-Accounts oder eines Staff-Mitglieds annehmen**
  (bisher nur online Spieler und bereits aktive Nicks gesperrt).

### Weitere Korrekturen
- **Clan-Namen werden jetzt validiert** (Länge, keine Formatierungscodes,
  kein führendes/nachfolgendes Leerzeichen).
- **`/pay` verlangt jetzt einen echten, bekannten Spieler** als Ziel und
  lehnt Null-/Negativbeträge korrekt ab, statt stillschweigend Münzen ins
  Leere zu senden.
- **Scoreboard-Platzhalter werden nicht mehr pro Zeile und Spieler neu
  aufgelöst**, sondern einmal pro Tick global plus ein schlanker
  Pro-Spieler-Durchlauf.
- **Discord `!run` erzwingt jetzt eine konfigurierbare, standardmäßig
  leere Aktions-Allowlist**, statt jedem Discord-Admin direkten Zugriff auf
  alle Actions zu geben.

## 0.61.0 — 2026-07-28

Nachgezogene sechste Etappe von Phase 1 (Addon-Plattform), die zuvor an
einem Session-Limit gescheitert war.

### Addon-Lifecycle: kein Classloader-Leck mehr bei fehlgeschlagenem Enable
- **Ein Addon, dessen `onEnable` wirft (oder dessen Hauptklasse fehlt),
  schließt jetzt seinen frisch erzeugten Classloader wieder**, statt ihn
  bei jedem (wiederholten) Enable-Versuch stillschweigend zu leaken.
- **`AddonInfo` trägt jetzt einen `failureReason`**, sichtbar auf der
  Addon-Seite im Dashboard direkt unter dem `FAILED`-Badge, statt nur im
  Node-Log nachschlagen zu müssen.

### Bridge-Values: korrektes Ownership, kein fremdes Löschen mehr
- **Published ein zweites Addon denselben Key, übernimmt es jetzt auch
  dessen Ownership.** Vorher blieb der alte Besitzer im Ownership-Set
  stehen — wurde er später deaktiviert, löschte `unpublishOwner` den
  längst von einem anderen Addon übernommenen Wert mit.
- **Neue `unpublishBridgeValue(key)`** auf `AddonContext`, um einen
  einzelnen Wert gezielt zurückzuziehen, statt nur alle Werte eines
  Addons auf einmal.
- **`GET /internal/bridge-values?serviceId=…` fällt jetzt fail-closed
  aus**, wenn sich die `serviceId` nicht auflösen lässt (unbekannter oder
  bereits gestoppter Dienst): leere Antwort statt — wie zuvor — der
  kompletten ungefilterten Werteliste aller Addons.

## 0.60.0 — 2026-07-28

Erste Etappe der Kernplattform-Härtung: Sicherheit, Vertrauensstellung
gegenüber PaperMC/Velocity, Storage-Haltbarkeit, spielerbezogene Identität
und Node-Robustheit. Fünf Bereiche, gemeinsam entwickelt und verifiziert
(442 Tests, KDoc-Gate grün), daher als ein Release gebündelt.

### Sicherheit: Pro-Service-Tokens statt eines geteilten Admin-Tokens
- **Jeder verwaltete Dienst bekommt sein eigenes, zufälliges Token**
  (`ServiceTokenRegistry`, 32 Byte `SecureRandom`) statt des bisherigen
  einen Admin-Tokens, das in jeden gestarteten Prozess injiziert wurde.
  Ein kompromittierter Spielserver kann damit nicht mehr das ganze Netzwerk
  administrieren (Tasks anlegen, Bans setzen, Dienste stoppen).
- **Drei Berechtigungsstufen für Routen**: `authorize` (Dashboard-Routen
  mit Permission-Node), `requireAdmin` (nur Admin-Token/Admin-Account),
  neu `requireBridge` (nur `/internal/*`-Bridge-Routen, akzeptiert Admin-
  Token oder das zur Route passende Service-Token). Alle 19 bestehenden
  `/internal/*`-Routen sind auf `requireBridge` umgestellt.
- **Benannte Admin-Accounts** über den Permission-Node `helix.admin`,
  getrennt vom statischen Break-Glass-Token. `PanelPrincipal.viaStaticToken`
  unterscheidet in Audit-Einträgen, ob über einen Account oder das
  Notfall-Token gehandelt wurde.
- **REST-Aktionen tragen jetzt einen echten `actor`**, statt generisch als
  „rest" zu erscheinen.
- **Neuer Rate-Limiter** und Sicherheits-Header (`X-Frame-Options: DENY`,
  CSP `frame-ancestors 'none'`, HSTS bei TLS) auf allen Control-Routen.

### Vertrauensstellung: moderne Velocity-Forwarding statt BungeeCord
- **Modernes Velocity-Forwarding mit generiertem Secret** ersetzt das
  veraltete BungeeCord-Forwarding.
- **Backends binden nur noch auf Loopback**, PaperMC-Server-Jars werden
  per SHA-256 gegen Mojangs Manifest verifiziert, und ein expliziter
  EULA-Zustimmungs-Schritt ist jetzt Pflicht vor dem ersten Start.

### Storage: atomare Schreibvorgänge
- **Alle Storage-Schreibvorgänge sind jetzt atomar** (Temp-Datei + fsync +
  `ATOMIC_MOVE`) mit einer Generation `.bak`-Fallback, falls ein Schreiben
  mitten im Prozess abbricht — verhindert korrupte oder halb geschriebene
  JSON-Dateien nach einem harten Node-Absturz.
- **Neue `SchemaMigrator`-Konvention** in `helix-api` für zukünftige
  Datenformat-Migrationen.

### Identität: UUID statt Name als Primärschlüssel
- **Neue node-weite Identitäts-Registry** (`IdentityRegistry`) bildet
  UUID auf den zuletzt bekannten Namen ab und wird bei jedem Login
  aktualisiert. Ein Namenswechsel oder ein von Mojang recycelter Name
  erbt dadurch nicht mehr die Bans oder Rechte des Vorbesitzers.
- **Bans, Rechte, Verwarnungen und Freundeslisten** werden schrittweise
  von namensbasierten auf UUID-basierte Schlüssel umgestellt, sobald die
  UUID eines Spielers bekannt ist.

### Node-Robustheit
- **Heartbeat-Watchdog** erkennt hängende Node-Zyklen.
- **Scheduler in zwei Executors aufgeteilt**, damit ein langsamer Task
  nicht den Heartbeat blockiert.
- **Storage-Backend startet mit Backoff-Retry** statt sofort
  aufzugeben, wenn der Datenspeicher beim Boot kurzzeitig nicht
  erreichbar ist.
- **Echte Port-Verfügbarkeitsprüfung** (`PortAllocator`) statt reiner
  Annahme, ein Port sei frei.
- **Verwaiste Service-Workspaces werden aufgeräumt**
  (`OrphanWorkspaceSweeper`) — schließt die Lücke, in der ein hart
  abgestürzter Dienst sein Temp-Verzeichnis für immer hinterlässt.
- **Audit-Log schreibt jetzt asynchron mit Rotation**, blockiert den
  Aufrufer nicht mehr bei einem langsamen Sink und verwirft im
  Überlastfall den ältesten wartenden Eintrag statt zu blockieren.
- **Shutdown parallelisiert** mit größerem Zeitbudget pro Dienst.

## 0.59.0 — 2026-07-28

### Audit-Trail: echte Spieler-Attribution + filterbare Audit-Seite
- **Spieler-Commands werden jetzt mit dem echten Namen auditiert** statt
  generisch als „bridge". Jeder In-Game-Befehl (Kick, Ban, Clan, …) landet
  im Audit-Log unter dem Namen des ausführenden Spielers, nicht mehr
  anonym.
- **Neue Panel-Seite „Audit"**: filterbar nach Kategorie, Spieler/Actor
  und freier Suche (Aktion, Service-ID, Details). Die Node-Route
  `GET /audit` unterstützt dafür neu `actor`- und `search`-Parameter.
- **Navigation umbenannt**: „Logs" → „Launcher-Logs" (die rohe
  Node-Konsole, unverändert), bisheriges „Audit" → „Logs" (die einfache,
  ungefilterte Tabelle bleibt erhalten), neue „Audit"-Seite mit den
  Filtern oben.

### Bann-Snapshot mit TTL: Bans wirken auch bei kurzem Node-Ausfall
- Die Velocity-Bridge cached jetzt alle 5 Sekunden eine Kopie der aktiven
  Bans (`GET /internal/ban-snapshot`, proxied vom Bans-Addon, leer ohne
  Addon). Schlägt die Live-Prüfung beim Login fehl (Node kurzzeitig
  unerreichbar), wird zuerst der bis zu 2 Minuten alte Cache geprüft —
  ein bekanntermaßen gebannter Spieler kommt damit nicht mehr durch das
  Neustart-Fenster. Unbekannte Namen bleiben wie bisher fail-open.

## 0.58.0 — 2026-07-27

### Clans: Tag-Verifizierung
- **Clan-Tags müssen verifiziert werden, bevor sie sichtbar sind:**
  Nametag-Suffix und Sidebar-`{clan}` zeigen den Tag erst, nachdem ein
  Admin ihn freigegeben hat. Frische Clans und jede spielerseitige
  Tag-Änderung (`/clan tag`) starten unverifiziert; die Spieler sehen
  einen Pending-Hinweis (auch in `/clan info`), Online-Mitglieder werden
  bei der Freigabe benachrichtigt (de/en).
- **Freigabe:** Verify/Unverify-Buttons im Clans-Panel (Liste + Detail,
  Status-Spalte) bzw. Actions `clan.verify <clan>` /
  `clan.unverify <clan>`. Ein Admin-`clan.settag` gilt automatisch als
  freigegeben. Bestehende Clans starten nach dem Update unverifiziert und
  müssen einmalig freigegeben werden.

## 0.57.0 — 2026-07-27

### Clans: Panel-Seite mit Verwaltung
- **Neue Dashboard-Seite „Clans"** (Extensions, Permission
  `helix.panel.addon.clan`): Liste aller Clans mit Suche, Tag,
  Mitgliederzahl und Bank; Detail-Ansicht mit Mitgliedern und Rollen;
  Verwaltung direkt im Panel — Tag ändern (validiert, eindeutig),
  Mitglied entfernen (Owner geschützt), Ownership übertragen, Clan
  auflösen.
- **Neue Admin-Actions:** `clan.detail <clan>` (JSON inkl. Mitglieder),
  `clan.settag <clan> <TAG>`, `clan.remove <player>`,
  `clan.transfer <clan> <player>`. Tag-Änderungen, Entfernungen und
  Auflösungen aktualisieren jetzt sofort die `clan.tag.*`-Bridge-Values
  der betroffenen Spieler (Sidebar/{clan}-Platzhalter).

## 0.56.0 — 2026-07-27

### Prefixes gehören jetzt den Permission-Gruppen
- **Gruppen haben Prefix & Namensfarbe** (`perm.group.prefix <gruppe>
  [prefix...]`, `perm.group.color <gruppe> [&c]`, editierbar im
  Permissions-Panel im Gruppen-Editor, Anzeige in der Gruppentabelle).
  Angezeigt wird die **höchstgewichtige Gruppe des Spielers** (inkl.
  vererbter Eltern; Spieler ohne Prefix-Gruppe fallen auf die
  Default-Gruppe zurück).
- **Bewusst entkoppelt von Permission-Nodes:** Die alte Lösung matchte
  Chat-Prefix-Regeln gegen Permissions — wer `*` hatte, bekam damit
  automatisch irgendeinen (den erstbesten) Prefix. Jetzt entscheidet
  ausschließlich die Gruppenzugehörigkeit + Gewicht.
- **Chat-Addon verschlankt:** Die permission-basierten Prefix-Regeln
  (`chat.prefix.add/list/remove`) sind entfernt; das Addon besitzt nur
  noch das Zeilenformat. Bestehende Regeln werden ignoriert (Legacy-Feld
  wird beim Laden übersprungen) — Prefixes bitte einmalig den Gruppen
  zuordnen.

## 0.55.2 — 2026-07-27

### Nick: Pre-Login-Prewarm + klarere Logs
- Nick-Prewarm hängt jetzt am `AsyncPlayerPreLoginEvent` statt am
  `PlayerLoginEvent` — behebt Papers
  „HorriblePlayerLoginEventHack"-Warnung (Reconfiguration-API blieb
  sonst serverweit deaktiviert).
- Der Rescope-Log sagt bei 0 Zuschauern jetzt explizit, dass der
  Nick-Nametag nur betrifft, was *andere* Spieler sehen — der eigene
  Nametag (z. B. via LabyMod-Anzeige) behält absichtlich den echten
  Namen.

## 0.55.1 — 2026-07-27

### Nick: Nametag-Rewrite robuster + diagnostizierbar
- **Hide/Show über zwei Ticks gestreckt** statt im selben Tick, damit der
  Client garantiert die saubere REMOVE → ADD(+Nick) → SPAWN-Sequenz
  verarbeitet (Live-Wechsel ohne Rejoin).
- **Klare Diagnose-Logs entlang der ganzen Kette:** Beim Bridge-Start
  „packetevents found/not installed", bei jedem Nick-Wechsel
  „Nick display for X: '…' → '…'" plus „re-sent to N viewers (profile
  rewrites so far: M)". Läuft ein Nick ohne installiertes packetevents,
  warnt die Bridge einmalig deutlich (Nametag bleibt dann Realname,
  Chat/Tab funktionieren) — packetevents kommt über das Guard-HXA
  (`paper/packetevents.jar`) auf den Server.
- Ohne packetevents wird der sinnlose Hide/Show-Zyklus übersprungen.

## 0.55.0 — 2026-07-27

### Nick = echte Verkleidung (inkl. Name über dem Kopf)
- **Exklusives Display-Profil:** Ein Nick ersetzt jetzt das komplette
  Profil statt nur der Namens-Komponente — Gruppen-Prefix und Clan-Tag
  der echten Identität leaken nicht mehr. Genickte Spieler erscheinen
  als Default-Spieler; der Disguise-Prefix ist konfigurierbar
  (`nick.disguise &7Spieler` / `nick.disguise clear`, Default: kein
  Prefix).
- **Nick über dem Kopf:** Die Paper-Bridge rewritet ausgehende
  `PLAYER_INFO_UPDATE`-Pakete via packetevents (softdepend, liegt durch
  IGuard ohnehin auf den Servern) — der Nametag zeigt den Nick, für
  bereits sichtbare Spieler erzwingt ein Hide/Show-Zyklus das Re-Senden.
  Beim Login wird die Nick-Map vorgewärmt, damit kein Echtname-Blitzer
  entsteht. Das eigene Info-Entry bleibt unangetastet (Chat-Session/
  Skin-Konsistenz). Ohne packetevents degradiert der Nick sauber auf
  Chat + Tablist.
- **Schnelle Propagation:** Das Nick-Addon publiziert
  `nick.name.<spieler>`-Bridge-Values; die Bridge gleicht sie jeden Puls
  (5 s) ab statt auf den 30-s-Display-Zyklus zu warten. Die
  Nametag-Teams hängen am *angezeigten* Namen, damit Prefix/Farbe auch
  am Nick-Tag kleben.

## 0.54.0 — 2026-07-27

### Chat-Kanäle: @team und @clan
- **`@team <nachricht>`** im normalen Chat schreibt in den Team-Chat,
  **`@clan <nachricht>`** in den Clan-Chat (Präfix case-insensitive).
  Die Paper-Bridge cancelt die öffentliche Chat-Nachricht und leitet den
  Text als Player-Command (`tc`/`cc`) an die Node weiter — Permissions
  (`helix.team.member`/`helix.clan`) prüft die Node wie bei jedem
  Command, Feedback (Usage, „kein Clan", fehlende Rechte) geht nur an
  den Absender. Nichts landet versehentlich im öffentlichen Chat.
- **Neuer Clan-Chat:** `/cc <nachricht>` erreicht alle Online-Mitglieder
  des eigenen Clans netzwerkweit, Format `[TAG] Sender: Nachricht`,
  Nachrichten de/en. Der Team-Chat `/tc` existierte bereits; beide
  Usage-Texte erwähnen jetzt die @-Kurzform.

## 0.53.0 — 2026-07-27

### Permissions: vollständiger Katalog + Auswahl-Modal im Panel
- **Permission-Audit über alle Addons:** Jede im Code verwendete
  Permission steht jetzt in der jeweiligen `addon.json` (und landet damit
  im Katalog des Permission-Panels). Einziger Fund: Helix-Guard
  deklarierte nur `iguard.alerts` — die 13 weiteren `iguard.*`-Nodes des
  Paper-Plugins (`ban`, `bypass`, `panel`, `spectate`, `reload`,
  `status`, `info`, `history`, `cases`, `case`, `replay`, `clients`,
  `confidence`) sind ergänzt. Die Chat-Prefix-Regeln nutzen bewusst
  betreiberdefinierte Nodes und bleiben undeklariert.
- **Permission-Auswahl im Panel als Modal:** Statt des Datalist-Eingabefelds
  öffnet „＋ Add permissions" (Gruppen-Editor) bzw. „＋ Grant permissions"
  (User-Detail) ein durchsuchbares Modal — Katalog nach Quelle/Addon
  gruppiert, Mehrfachauswahl per Checkbox, bereits gewährte Nodes
  ausgegraut markiert, Custom-Feld für Wildcards (`iguard.*`) und eigene
  Nodes, Negations-Schalter (`-node`). Bei Usern wird die Duration aus
  dem bestehenden Feld auf alle ausgewählten Nodes angewandt.
  Gruppen-Editoren bleiben über Reloads hinweg geöffnet.

## 0.52.0 — 2026-07-27

### Display-Namen: Prefix + Name + Suffix — überall
- **Neues Display-Name-Modell im Helix-User:** Der angezeigte Name setzt
  sich aus `prefix + color + name + suffix` zusammen. Konvention: Prefix
  gehört den Gruppen (Chat-Prefix-Regeln), Name ist der veränderbare Nick,
  Suffix gehört den Clans. `DisplayProfile` hat dafür die neue
  `name`-Komponente plus `displayName()`-Komposition.
- **Node merged Resolver komponentenweise** statt „erster Treffer
  gewinnt": Vorher verdrängten sich Chat-Prefix und Clan-Tag gegenseitig —
  deshalb fehlte der Clan-Tag im Chat. Jetzt komponieren Chat- (Prefix),
  Nick- (Name) und Clan-Addon (Suffix) einen gemeinsamen Display-Namen.
- **Neues Nick-Addon** (`helix.nick`): `/nick <name>` setzt den
  Anzeigenamen, `/nick off` stellt den echten Namen wieder her. Nicks
  persistieren, sind 3–16 Zeichen (Buchstaben/Ziffern/Unterstrich) und
  können keine Account-Namen online befindlicher Spieler oder fremde
  aktive Nicks imitieren. Admin-Actions: `nick.list`, `nick.clear`.
  Nachrichten de/en.
- **Nametag über dem Kopf zeigt jetzt Prefix + Suffix:** Die
  Display-Teams lagen nur auf dem Main-Scoreboard — sobald das
  Scoreboard-Addon jedem Spieler seine private Sidebar gab, sah niemand
  mehr die Teams (Nametags rendern aus dem Scoreboard des *Betrachters*).
  Die Bridge pflegt die Teams jetzt auf dem Main-Board und allen privaten
  Boards und seedet sie beim Anlegen neuer Boards.
- **Clan-Tag als Suffix** (`Name [TAG]` statt `[TAG] Name`), Chat-Format
  rendert `{suffix}` (bestehende Konfigurationen werden automatisch
  migriert), Tablist-Name und Paper-`displayName` nutzen den komponierten
  Namen, Sidebar-Platzhalter `{displayname}` zeigt ihn ebenfalls; neu:
  `{nick}`.
- Hinweis: Der Nick ersetzt den Namen in Chat, Tablist und
  Death-/Join-Messages. Der Name *im* Nametag über dem Kopf bleibt
  technisch der Account-Name (Prefix/Suffix/Farbe drumherum kommen an) —
  echtes Nametag-Spoofing braucht Paket-Rewriting und ist als Folgeschritt
  vorgesehen.

## 0.51.0 — 2026-07-27

### IGuard: False-Positive-Großputz (Recipe `shadow-v3`)
- **Normales Laufen/Springen/Sprint-Jumpen** flaggte über eine
  Phantom-Airborne-Kette: Der Kollisions-Scan (±1 Block) ist um die
  server-seitig *gesampelte* Position zentriert und hängt dem Paketstrom
  1–2 Ticks hinterher — ein Sprinter überholt den Scan, der Block unter
  ihm fehlt, `supports()` meldet „airborne", airTicks akkumulieren
  (bei 1.21.2+-Clients ohne Frische-Guard), und der nächste *normale
  Sprung* verfehlt das Takeoff-Gate → Fly/Speed-Flag mit großem Offset.
  Fixes: Scan auf ±2 verbreitert, Coverage-Guard (Frame außerhalb des
  Scans = „nicht prüfbar", nie Evidenz), airTicks-Frische-Guard auch für
  Tick-End-Clients, und Takeoff/Sprint-Jump-Impuls akzeptieren das
  Client-`onGround` als Fallback (Lügen fängt weiterhin Nofall).
- **`baseline()` nullte den Horizontal-Predictor** während Exemptions —
  der erste Frame nach Knockback (= jedem PvP-Treffer), Elytra-Landung
  oder Teleport-Grace verglich echtes Momentum gegen ein
  Stillstand-Budget. Seedet jetzt aus dem beobachteten Momentum.
- **`sprintbackwards`** feuerte bei 180°-Drehungen im Sprungflug
  (Momentum in der Luft ist richtungsfrei); zählt jetzt nur noch
  Boden-Ticks und braucht eine Streak von 3.
- **Scan-Cache-Invalidierung** bei Blockbruch/-platzierung in der Nähe:
  beim Sprint-Minen führte die veraltete Kollisionsbox des gerade
  abgebauten Blocks zu `phase`-FPs.
- **Fahrzeuge & Wasser:** Der Liquid-Sonderfall hat bisher *alle* anderen
  Exemptions storniert (Boot auf Wasser, Riptide im Wasser) und der
  Nofall-Zweig hat Exemptions nach 10 Airticks überstimmt — jeder Reiter/
  Boot-Passagier flaggte `nofall`/`jesus`. Beides behoben; Exemptions
  gelten jetzt absolut, `liquid` schaltet nur noch selektiv auf den
  Jesus-Check um.
- **Auf Entities stehen:** Boote, Minecarts und Shulker sind begehbare
  *Entities* ohne Block-Kollision — Spieler darauf flaggten Hover-Fly/
  Nofall. Der Sampler taggt jetzt `entity-support`; ebenso neu: `bed`
  (Bett-Bounce = legitimer Aufwärtsimpuls in der Luft).
- **Anti-Knockback:** Die KB-Absorption wurde nur auf nicht-exempten
  Frames gemessen, das Velocity-Paket selbst gewährt aber die Exemption —
  nach deren Ablauf las der Check „kein KB genommen" und flaggte jeden
  danach stehenden Spieler. Beobachtung läuft jetzt auf jedem Frame.
- **NoSwing:** Der Vanilla-Client sendet den Arm-Swing *nach* dem
  Attack-Paket — der erste Schlag nach >400 ms Pause flaggte immer.
  Jetzt zählt nur noch eine Serie von Attacks ohne zwischenzeitlichen
  Swing (Streak ≥ 3).
- **Autoclicker:** Wurde auf Block-/Dig-Paketen gemessen, die der Client
  tick-aligned flusht — normales Minen las sich als „perfekt regelmäßiger
  Autoclicker". Misst jetzt echte Attack-Pakete (inkl. Mob-Kills).
- **Reach/Rotation-Lag-Kompensation:** Bisher nur Ping/2; die ~100 ms
  Entity-Interpolation des Clients fehlten komplett — Reach-FPs in jedem
  laggy Chase-Fight. Das Ziel wird jetzt über das ganze Fenster gesampelt
  und gegen den günstigsten Frame bewertet.
- **Kreativ/Exempt-Spieler** liefen ungefiltert in die World-Checks
  (Instant-Break-Spam = `nuker`/`fastbreak`/`fastplace`). Block- und
  Interaktions-Checks respektieren jetzt Exemptions und Gamemode.
- **Kanten-Stand:** Die Feet-Box war um 2 cm geschrumpft — wer mit
  minimalem Überhang legal an einer Blockkante stand, galt als airborne
  (Hover-FP). Shrink jetzt 1 mm.
- **Feinschliff:** `fastbreak`-Fenster 74 ms → 24 ms (Haste+Efficiency-
  Brüche in 1–2 Ticks sind legal), `snapaim` 60° → 90° plus Streak ≥ 2
  (High-Sens-Flicks), `step`/`highjump` rechnen Jump-Boost ein,
  Bedrock-Gates für Timer-, Aim-, Inventar- und Inventory-Move-Checks
  (Geyser-Spieler laufen/kämpfen legal mit offenem Inventar).
- Recipe-Version auf `shadow-v3` gebumpt: Enforcement bleibt Shadow, bis
  das neue Rezept auf dem Bot-Harness kalibriert und in
  `sanctions.calibrated-recipe` freigegeben wurde.

## 0.50.0 — 2026-07-27

### Deutlich mehr Performance-Metriken im Dashboard
- **Die Node misst sich jetzt selbst** (bisheriger Blind-Spot): eigener
  CPU, Heap (used/max + Auslastung), Non-Heap, System-Load-Average,
  Threads (aktuell/Peak), GC (Anzahl + Gesamtzeit), Uptime. Neuer Endpoint
  `GET /api/v1/health` mit der kompletten Momentaufnahme.
- **Aggregierte Ressourcen-Trends:** Gesamt-CPU und Gesamt-RAM aller
  laufenden Services sowie Node-CPU/Heap/System-Load wandern in die
  Metrik-Zeitreihe (`MetricSample` erweitert, wire-kompatibel).
- **Interne Health-Indikatoren:** Permission-Cache-Größe, Scheduled-Jobs,
  und **überfällige Heartbeats** (laufende Services ohne Heartbeat > 15 s)
  als direkter „hängt etwas?"-Indikator.
- **Dashboard** in gruppierte Karten umgebaut (Network / Node / Services /
  API): neue Node-Health-Kachel-Grid (Heap-Meter, Load/Cores, Threads, GC,
  Uptime, Stale-Heartbeat-Warnung), neue Trend-Graphen (Node-CPU, Node-Heap,
  System-Load, Services-CPU gesamt, Services-RAM gesamt), ausgebaute
  API-Gruppe (p95, Req/min, Fehlerrate rot bei >0). „Keine Daten"-Fälle
  (−1/null) werden als Lücken bzw. Platzhalter sauber dargestellt.

## 0.49.0 — 2026-07-26

### Neue Addons: Clans, Scoreboard, NPCs — plus ECO-Startguthaben
- **Economy:** Neue Spieler starten mit **1000 Coins**. Balance wird als
  Bridge-Value publiziert (`economy.balance.<name>`) für den Scoreboard-
  Platzhalter `{balance}`.
- **Clan-System** (`helix.clan`): `/clan` mit create/invite/join/leave/
  kick/promote/demote/disband/setowner/tag, `[TAG]`-Prefix in Chat & Tab
  über den DisplayResolver, und eine **Clan-Bank**, die echtes Geld atomar
  über die Economy bewegt (Einzahlung nur bei ausreichendem Guthaben, der
  Bank-Zähler folgt der tatsächlichen Transaktion). Tag als Bridge-Value
  für den Scoreboard-Platzhalter `{clan}`.
- **Scoreboard** (`helix.scoreboard`): **pro Task/Server** einstellbares
  Sidebar-Board, im Panel editierbar (Titel + Zeilen + Intervall), gerendert
  von der bestehenden Paper-Bridge (wie Tablist — kein extra Plugin). Live-
  Platzhalter ({player}, {online}, {ping}, {tps}, {balance}, {clan}, …),
  Duplikat-Zeilen-Fix, per-Task-Auswahl über `HELIX_TASK`.
- **NPCs** (`helix.npc` + gebündelte Paper-Komponente): das INpc-Framework
  eingebunden — `/npc create|delete|skin|look|hologram|interact|tp`,
  netzwerkweit persistent im Node-Storage, pro Task, Klick-Aktionen (z.B.
  Server-Wechsel). INpc bleibt als Framework für andere Addon-Entwickler
  nutzbar (vendored Jar). **Hinweis:** die Paper-Server müssen auf **Java 24+**
  laufen (INpc-Bytecode), sonst `UnsupportedClassVersionError`.

## 0.48.0 — 2026-07-26

### Guard-Detection Stufe 3: Bedrock/Geyser-Erkennung
- Bedrock-Spieler (Geyser/Floodgate, erkannt an der Floodgate-UUID mit
  Null-High-Bits — dependency-frei) laufen mit anderer Bewegungsphysik als
  Java; die Java-Prediction hätte sie systematisch als False Positive
  geflaggt. Movement-Checks werden für Bedrock jetzt ausgesetzt,
  deterministische Protokoll-, Combat- und World-Checks bleiben aktiv.

## 0.47.0 — 2026-07-26

### Guard-Detection Stufe 1+2: Offset-Engine + Setback-Primärreaktion
- **Movement-Checks (fly/speed) auf Grim-Prinzip umgestellt:** statt „ein
  vorhergesagter Wert + geratene Toleranz → Flag mit fester Gewichtung"
  jetzt Unsicherheits-Intervall + **Offset** — wie weit die beobachtete
  Bewegung *außerhalb* des physikalisch Möglichen liegt. Offset 0 = sicher
  legal. Die Violation-Gewichtung IST der Offset (selbst-kalibrierend,
  deterministisch): ein Grenzfall akkumuliert kaum und decayt weg, ein
  klarer Cheat akkumuliert schnell.
- **Knockback-Unsicherheit (decaying velocity window):** nach Server-
  Velocity/Explosion wird das Möglichkeits-Intervall um den Knockback
  erweitert, der der Client noch trägt, und decayt über die Folge-Ticks —
  behebt den klassischen False Positive, wenn ein zurückgeworfener Spieler
  kurz „zu schnell/zu hoch" wirkt.
- **Setback ist jetzt Primärreaktion für ALLE Movement-Checks** (nicht nur
  fly/speed): ein Movement-Cheat wird durch Zurücksetzen neutralisiert, ein
  False Positive kann damit prinzipiell niemanden bannen. Bans bleiben der
  konservativen Confidence-Pipeline (deterministisch/mehrere Familien)
  vorbehalten.
- Streak-/Repeat-Bonus entfernt (verstärkte Grenzfälle) — wiederholte
  Offsets akkumulieren ohnehin natürlich in der VL. `offset`/`knockback`
  stehen als Evidenz im Incident für Panel und Replay.

## 0.46.3 — 2026-07-26

### Fix: Replay-Crash durch relozierte rune-Bibliothek
- Die vendored `rune`-Jar stammte aus IGuards altem Shadow-Jar, in dem
  Kotlin nach `de.tytoss.iguard.lib.kotlin` relokiert war — ihr Bytecode
  zeigte auf ein Paket, das das neue Modul nicht bündelt
  (`NoClassDefFoundError` beim Region-Export im Replay). Die Jar wurde
  per ASM auf Standard-Kotlin-Pakete zurückgemappt (0 echte
  Typ-Referenzen verbleiben); das Replay baut die Region wieder auf.

## 0.46.2 — 2026-07-26

### Guard: Replay mit Welt-Kopie, Zuschauen mit freier Kamera
- Die **Welt wird wieder rekonstruiert**: Incidents tragen jetzt die
  World-UUID durch den Node-Contract (beim Schreiben über den Spieler
  aufgelöst, von der Node gespeichert und abgefragt) — das Replay kopiert
  damit das Terrain der Szene wieder in die Replay-Welt statt im Void zu
  spielen.
- **Kein Festhalten mehr am Spieler:** `/iguard spectate` teleportiert
  dich an einen Aussichtspunkt hinter/über den Verdächtigen mit Blick auf
  ihn — freie Spectator-Kamera, kein `spectatorTarget`-Lock. Im Replay
  ist die Verfolgerkamera jetzt standardmäßig aus (per „Follow"-Button
  weiterhin zuschaltbar).

## 0.46.1 — 2026-07-26

### Helix-Guard: Panel-Texturen im Netzwerk-Pack
- Neuer Java2D-Generator zeichnet die IGuard-Panel-Texturen (Header-Bar
  mit Schild-Icon, dunkles Full-Window-Background mit Slot-Raster) und
  baut daraus das `iguard`-Namespace-Pack inkl. Spacing-Font und
  Text-Zeilen — als `pack.zip` in der Guard-HXA, automatisch ins
  Netzwerk-Pack gemerged. Das In-Game-Panel (`/iguard panel`) rendert
  damit ohne weiteres Zutun.

## 0.46.0 — 2026-07-26

### Zentrales Resource-Pack: ein Netzwerk-Pack für alle Addons
- Die Node fügt die `pack.zip`s **aller aktivierten Addons** zu einem
  deterministischen Netzwerk-Pack zusammen (`Helix/packs/network.zip`,
  generierte `pack.mcmeta`; bei Pfad-Konflikten gewinnt das nach Addon-Id
  erste Addon, ein Warn-Log nennt beide) — neu gebaut bei Node-Start und
  bei jedem Addon-Enable/-Disable.
- Die Control-API liefert es öffentlich unter `/api/v1/packs/network.zip`
  (+ `.sha1`) aus; die Velocity-Bridge verteilt es **beim Proxy-Join** an
  jeden Spieler und erneut an alle Online-Spieler, sobald sich die SHA-1
  ändert (`GET /internal/pack` im 5s-Sync). Zwei Addons mit Packs
  überschreiben sich damit nicht mehr gegenseitig den Pack-Slot des
  Clients.
- Download-URL-Auflösung wie bewährt: `network.packurl <url|->`
  (persistiert, als Bridge-Value `network.pack_url` publiziert) →
  `HELIX_PACK_URL` → Virtual-Host des Spielers + Control-Port →
  Control-URL.
- BetterMSGs wendet sein Pack nicht mehr selbst an; die Action
  `bettermsgs.packurl` entfällt zugunsten von `network.packurl`. Die
  Einzel-Packs bleiben unter `/api/v1/packs/<addon-id>.zip` abrufbar.

### Helix-Guard: IGuard-Quellcode vollständig im Repo
- Der komplette IGuard-Quellbaum lebt jetzt als Modul
  `helix-addon-guard-paper` im Helix-Cloud-Repo — das externe
  IGuard-Verzeichnis wird nicht mehr gebraucht und kann gelöscht werden.
- Dabei Helix-nativ verschlankt: PostgreSQL-Backend, eigenes
  Web-Dashboard und die `storage`/`database`/`dashboard`-Config-Sektionen
  sind entfernt — der `HelixNodeStore` (Node-Storage über die Control-API)
  ist der einzige Persistenz-Pfad; `bans.provider: native` ist ein Alias
  des Helix-Providers. `rune` liegt als vendored Jar im Modul, IGui kommt
  über den Composite-Build, packetevents bleibt gebündelte
  Plugin-Abhängigkeit. 131 fehlende KDoc-Blöcke ergänzt, 30 portierte
  Tests grün; die HXA enthält null postgres-/hikari-Klassen.

## 0.45.0 — 2026-07-26

### Helix-Guard: komplett über den Storage-Provider — keine DB-Zugriffe aus den Servern
- IGuard hat jetzt ein Storage-Interface mit zwei Backends: der bisherige
  PostgreSQL-Pfad (Standalone, unverändert) und ein neuer **Helix-Mode**
  ohne jede Datenbankverbindung — alle Writes (Violations, Incidents,
  Replays, Punishments, Bans) und Reads laufen gebatcht über die
  Control-API zur Node (`guard.store.*`/`guard.query.*`) und werden dort
  **über den Storage-Provider** persistiert (json, Postgres oder Mongo —
  wie in node.toml konfiguriert). `storage.mode: auto` erkennt die
  Helix-Umgebung automatisch an `HELIX_CONTROL_URL`.
- Netzwerkweite Durchsetzung übernimmt die Node: Ban → sofortiger Kick +
  **Join-Gate** (übersetzter Ban-Screen, UUID/Name-Match, Auto-Pruning
  abgelaufener Bans); Incident → Alert an alle Mods mit `iguard.alerts`
  auf allen Servern, in deren Sprache. IGuards Velocity-Outbox-Plugin
  entfällt — `velocity.jar` ist aus der HXA entfernt.
- In Helix-Mode außerdem: IGuards eigenes Web-Dashboard deaktiviert
  (das Helix-Panel übernimmt), IGui-Texturen in einer File-DB statt
  Postgres, `database.*` komplett aus Config und Panel entfernt —
  gerendert wird `storage.mode: "helix"`.

## 0.44.0 — 2026-07-26

### Helix-Guard: Datenbank kommt aus der Node
- IGuards PostgreSQL-Verbindung wird nicht mehr im Panel eingestellt,
  sondern aus der zentralen Storage-Konfiguration der Node geerbt
  (`node.toml` → `[storage]` mit `mode = "postgres"`): Host/Port/DB/User/
  Passwort/SSL werden aus der JDBC-URL abgeleitet und in jede gerenderte
  IGuard-`config.yml` geschrieben. Im Panel bleibt nur `database.pool-size`.
- Läuft die Node nicht auf Postgres, warnt jedes Apply deutlich —
  IGuard setzt Postgres voraus.
- Neue Addon-API: `context.storageConnection()` liefert Addons die
  zentrale Storage-Verbindung (Mode, URL, User, Passwort, DB, Pool).

## 0.43.1 — 2026-07-26

### Fix: IGuard deaktivierte sich beim Service-Start
- Der Default für `database.password` war das Literal
  `${IGUARD_DB_PASSWORD}` — IGuards Env-Substitution wirft bei fehlender
  Variable und das Plugin deaktiviert sich. Default ist jetzt leer;
  Zugangsdaten kommen aus dem Guard-Panel.
- Das Guard-Addon verteilt seine gerenderte `config.yml` jetzt schon beim
  Enable in alle Templates/Workspaces — Services starten nie mehr auf
  IGuards Bundled-Default (falsche `server-id`, Env-Falle).

## 0.43.0 — 2026-07-26

### Helix-Guard — IGuard-Anticheat als Addon, komplett Panel-konfigurierbar
- Neues Addon `helix.guard`: bündelt das IGuard-Anticheat als eine HXA —
  `paper.jar` (IGuard Shadow-Jar), `velocity.jar` (IGuards netzwerkweiter
  Ban-/Alert-Layer), `paper/packetevents.jar` (Fat-Jar der Pflicht-
  Abhängigkeit) und `addon.jar` (Node-Teil). Ein Drop in `Helix/addons/`
  verteilt alles automatisch auf Paper- UND Velocity-Services.
- **Alle 179 IGuard-Einstellungen im Web-Panel** (neue Panel-Seite
  „Guard"): Alerts, alle 31 Checks (enabled/alert-vl/setback-vl/decay),
  Confidence, Bans inkl. Command-Templates, Discord-Notifications,
  Exemptions, Datenbank, Dashboard, Worker/History — gruppiert, typisiert
  (Checkbox/Zahl/Text), mit „overridden"- und „restart"-Badges.
- Verteilung: Bei jedem Save rendert der Node die komplette
  `config.yml`, schreibt sie in alle Templates + statischen Workspaces
  und schickt laufenden Paper-Services `iguard reload` über die Konsole
  (dynamische Werte greifen sofort; statische melden „requires service
  restart"). Die `server-id` wird pro Service automatisch über
  `${HELIX_SERVICE_ID}` aufgelöst.
- Plattform: HXAs können jetzt zusätzlich `velocity.jar` (Velocity-
  Komponenten) und `paper/<name>.jar` (mitreisende Plugin-Abhängigkeiten)
  enthalten; neue Action `service.command <id> <line...>`.
- Build-Automatik: `:helix-addon-guard:packageHxa` baut IGuard und sein
  Velocity-Plugin aus dem Geschwister-Checkout `../IGuard` (IGui wird
  nach mavenLocal publiziert; `org.fsqrt.rune:base` muss in mavenLocal
  liegen) und lädt packetevents von codemc.

## 0.42.1 — 2026-07-25

### Fix: Resource-Pack-Download ("1 out of 1 packs failed")
- Die Pack-Route lag im Auth-Bereich der API (401 für Clients) — sie ist
  jetzt wirklich öffentlich.
- Die Download-URL ist jetzt client-tauglich: Docker-/localhost-Adressen
  aus `HELIX_CONTROL_URL` erreichen den Minecraft-Client nicht. Neue
  Kette: per Action `bettermsgs.packurl <url|->` konfigurierte URL →
  `HELIX_PACK_URL`-Env → **automatisch die Adresse, mit der der Spieler
  verbunden ist** (Virtual Host + Control-Port) → Control-URL.
- End-to-end verifiziert: Download ohne Auth, SHA-1 identisch,
  konfigurierte URL erreicht die Paper-Komponente über Bridge-Values.

## 0.42.0 — 2026-07-25

### BetterMSGs — Handy-GUI für private Nachrichten
- Neues Addon `helix.bettermsgs`: `/msg` öffnet einen **Handy-Homescreen**
  (IGui-Font-Textur als Hintergrund) mit Spielerköpfen als „Apps" —
  Kontakte zuerst (mit Unread-Badge ✉), dann Online-Spieler, paginiert.
  Klick auf einen Kopf öffnet den **Discord-artigen Chat**: Nachrichten
  als Kopf+Text-Items, eigene rechts, fremde links, Zeitstempel,
  Text-Wrapping in der Lore.
- **Spieler-Inventar als Chatfläche:** 8 sichtbare Nachrichten über
  Chest- und Inventar-Zeilen; das echte Inventar wird vorher gesichert
  (zusätzlich crash-sicher auf Platte) und beim Schließen/Quit/Disable
  wiederhergestellt. Hotbar = Steuerleiste (Zurück, Ältere, Schreiben,
  Neuere, Schließen).
- **Scrollen:** Buttons + eine **gezeichnete Scrollbar** — der Thumb ist
  eine Font-Textur in 8 Positions-Varianten und wandert im Titel mit der
  Scroll-Position. Schreiben per Chat-Eingabe (`cancel` bricht ab),
  Live-Refresh jede Sekunde, Fokus unterdrückt Notifications.
- **Netzwerkweit:** Konversationen liegen auf der Node (500er-Cap,
  Unread-Zähler); Offline-/Fremdserver-Empfänger bekommen eine klickbare
  Notification (`[öffnen]` → `/msg <sender>`). Alle Texte en+de über das
  Translation-System.
- **Alle Texturen selbst gezeichnet:** Java2D-Generator
  (`:helix-addon-bettermsgs-paper:generatePack`) baut Handy-, Chat- und
  Scrollbar-Texturen + `bettermsgs:`-Fonts deterministisch zur Buildzeit.
- **Plattform:** HXA-Addons können jetzt eine Paper-Plugin-Komponente
  (`paper.jar`, wird als `plugins/HelixAddon-<id>.jar` in aktive
  Paper-Workspaces installiert und bei Deaktivierung entfernt) und ein
  `pack.zip` mitliefern, das die Control-API öffentlich unter
  `/api/v1/packs/<id>.zip` (+ `.sha1`) served; Override der Client-URL
  via `HELIX_PACK_URL`. IGui ist als Composite-Build (`../IGui`)
  eingebunden; die Textur-Metadaten nutzen eine File-Datenbank statt
  PostgreSQL.

## 0.41.0 — 2026-07-25

### Broadcasts beim Backend-Restart
- Vor dem Backend-Restart geht ein netzwerkweiter Broadcast raus
  (`helix.translations.network.restart.backend.start`, de: „Die
  Backend-Server starten neu — es kann kurzzeitig zu Lags kommen."),
  mit 1,5 s Grace-Zeit, damit die Proxy-Long-Polls ihn vor dem
  Control-API-Stopp ausliefern.
- Nach erfolgreicher Übernahme broadcastet der Nachfolger
  (`…restart.backend.done`, de: „Backend-Restart beendet."). Die
  Nachricht wartet in der Command-Queue, bis die headless überlebenden
  Proxies re-pollen — sie geht also auch dann nicht verloren, wenn der
  Node ein paar Sekunden weg war.
- Beide Texte sind pro Sprache im Panel editierbar und werden je
  Empfänger in dessen Sprache aufgelöst.

## 0.40.1 — 2026-07-25

### Fix: Backend-Restart im Terminal stoppte alle Services
- Der Nachfolger-Prozess erbte das Terminal-stdin; nach dem Exit des alten
  Prozesses lieferte das TTY `EIO` → unbehandelte `IOException` im
  Main-Thread → JVM-Shutdown → der Shutdown-Hook stoppte die frisch
  adoptierten Services, während der Auto-Scaler parallel neue startete.
- Fix (dreifach): Der Nachfolger bekommt `stdin=/dev/null` statt des
  geerbten Terminals (stdout/stderr bleiben auf der Konsole; Steuerung
  nach dem Restart über Panel/REST/in-game). Die CLI behandelt
  Lese-Fehler wie EOF statt zu crashen. Der Shutdown-Hook setzt jetzt
  das Stopping-Flag und stoppt den Scheduler, damit der Auto-Scaler
  Services nicht wiederbelebt, während sie heruntergefahren werden.
- Reproduziert und verifiziert unter echtem PTY: Service überlebt den
  Backend-Restart mit identischer Wrapper-PID, keine IO-Errors.

## 0.40.0 — 2026-07-25

### Linux-Install-Script & systemd-Integration
- **`tools/install.sh`**: Ein-Kommando-Installation auf Linux-Servern
  (`curl … | sudo bash`). Installiert nach `/opt/helix` (konfigurierbar):
  Launcher.jar (GitHub-Release-Asset, `--jar`-Datei oder Source-Build),
  Temurin-JDK falls das System-Java < 24 ist, `node.toml` mit
  **zufälligem Admin-Token** (0600), dedizierter Systemuser, systemd-Unit
  (alternativ `--no-systemd` mit screen-freundlichem `start.sh`).
- **systemd-bewusste Restarts:** Als Service (`HELIX_SYSTEMD=1` oder
  `INVOCATION_ID` erkannt) spawnt die Node den Nachfolger nicht mehr
  selbst, sondern beendet sich mit Exit-Code `10` — die Unit
  (`Restart=on-failure`, `KillMode=process`) startet den Nachfolger, die
  headless laufenden Services überleben und werden adoptiert. Eine
  service-managed Node fährt bei stdin-EOF nicht mehr herunter.
- Verifiziert: Exit-Code-10-Delegation, Überleben des Wrappers, Adoption
  mit identischer PID durch den Nachfolger, sauberer `platform.stop`.

## 0.39.0 — 2026-07-25

### Backend- & Launcher-Restart (`/helix <modul> restart`)
- **`platform.restart`** bzw. in-game **`/helix backend restart`**
  (Permission `helix.admin`): Der Node-Prozess startet sich selbst neu,
  während alle Services **headless weiterlaufen**. Der Nachfolger-Prozess
  adoptiert sie wieder — Prozess-Services über die persistierte Wrapper-PID
  (`Helix/services/registry.json`, wird bei jedem Lifecycle-Wechsel
  gespiegelt), Docker-Services über ihren Container-Namen. Bridges
  verbinden sich über ihre bestehenden Loops automatisch neu.
- **Der Cache überlebt den Restart:** Maintenance-Flag, Spieler-Roster,
  native Permission-Snapshots, Scheduler-Timing (keine doppelten
  Daily-Jobs) und die Metrik-Historie der Dashboard-Graphen werden über
  `Helix/restart-state.json` an den Nachfolger übergeben.
- **`launcher.restart`** bzw. **`/helix launcher restart`**: voller
  Neustart — Services stoppen sauber, eine frische `Launcher.jar` startet
  anstelle der alten (nimmt eine ersetzte Jar-Datei mit, z.B. nach Update).
- Respawn erbt Konsole/Arbeitsverzeichnis (`HELIX_RELAUNCH=1`); ein
  respawnter Node ohne Terminal beendet sich nicht mehr bei stdin-EOF.
- Neue E2E-Tests: `tools/e2e-restart.sh` (headless-Überleben, Re-Adoption
  mit identischer PID, State-Restore, Konsole an adoptierten Service) und
  `tools/e2e-launcher-restart.sh`.

## 0.38.0 — 2026-07-25

### Scheduled Restarts mit Chat-Countdown
- Neue Actions `service.restart <id> [delaySeconds]` und
  `task.restart <task> [delaySeconds]` (Default 60 s): angekündigter
  Neustart mit netzwerkweitem Chat-Countdown (Marken 600/300/120/60/30/10/
  5/3/2/1 s) — die Announcements erscheinen in der Sprache jedes Spielers
  (`helix.translations.network.restart.warn`/`.now`, `{target}`/`{seconds}`).
- Task-Restarts laufen **rollierend**: ein Service nach dem anderen wird
  gestoppt, auf Terminierung gewartet und ersetzt — übernimmt der
  Auto-Scaler den Ersatz bereits, wird kein doppelter Service gestartet.
- Über die bestehende **Schedules**-Seite planbar (z.B. täglicher
  4-Uhr-Restart: Action `task.restart`, Argumente `Lobby, 300`,
  `dailyAt 04:00`).

## 0.37.0 — 2026-07-25

### Mehrsprachiges Translation-System
- Neuer netzwerkweiter Übersetzungs-Store: **alle** player-facing Nachrichten
  (Disconnect-Screens, Proxy-Commands, Kicks/Broadcasts, alle Addon-Messages)
  liegen als flache Keys `helix.translations.<owner>.<key>` mit Werten **pro
  Sprache** vor. Ausgeliefert mit Englisch (Default) und Deutsch, weitere
  Sprachen im Panel anlegbar.
- **Spieler wählen ihre Sprache** mit `/helix language <code>`; beim First
  Join wird die Minecraft-Client-Sprache übernommen (`de_DE` → `de`).
  Präferenzen persistieren über den zentralen Storage.
- Neue Panel-Seite **Translations** (ersetzt **Messages**, Permission
  `helix.panel.translations`): Sprachen verwalten (anlegen, Default setzen,
  entfernen), Keys filtern/editieren/resetten, eigene Keys unter
  `helix.translations.custom.*` anlegen/löschen.
- Addon-API: `context.localizedMessages(mapOf("en" to …, "de" to …))` und
  `Messages.formatFor(player, key, …)` — alle mitgelieferten Addons sind
  migriert und vollständig auf Deutsch übersetzt. `context.messages(…)`
  bleibt als einsprachige Kurzform (registriert als Englisch).
- Velocity-Bridge rendert per Spieler-Sprache (Snapshot-Sync alle 5 s):
  Maintenance-/Full-Screens, Join-Deny, Kick-Redirect, Command-Antworten.
  `ProxyCommand` kann jetzt einen `translationKey` tragen — Broadcasts
  werden je Empfänger in dessen Sprache aufgelöst.
- Messages-Polish: `/lobby`, `/server`, `/servers` antworten übersetzt in
  MiniMessage mit `{prefix}`; die `/servers`-Liste ist **klickbar**
  (click-to-connect mit Hover). Alte Proxy-Screens (`proxy`-Bundle) werden
  automatisch migriert.
- REST: `GET/POST /translations`, `DELETE /translations/{key}`,
  `POST/DELETE /translations/languages`, intern `GET /internal/translations`
  und `POST /internal/player-language` (`GET/POST /messages` entfällt).

## 0.36.0 — 2026-07-22

### Live-Log-Streaming (SSE)
- Node-Log und Service-Konsole streamen jetzt live über Server-Sent Events
  (`GET /logs/stream`, `GET /services/{id}/logs/stream`) statt 2s-Polling —
  neue Zeilen erscheinen in <500 ms.
- Implementiert ohne Zusatz-Plugin (`text/event-stream` via Writer);
  LogBuffer liefert präzise Offsets, Service-Logs werden delta-erkannt;
  Keep-Alive-Kommentare halten die Verbindung offen.
- Dashboard (Logs-Seite + Service-Konsole) konsumiert den Stream über
  fetch/ReadableStream (EventSource kann keine Auth-Header) und fällt bei
  Fehlern automatisch aufs bisherige Polling zurück.

## 0.35.0 — 2026-07-22

### Per-Service CPU & RAM
- Die Bridges messen jetzt Ressourcen des Server-JVM (Heap used/max via
  `Runtime`, Prozess-CPU via `OperatingSystemMXBean`) und melden sie im
  Heartbeat — identisch für Prozess- und Docker-Services, ohne /proc-Parsing.
- `HeartbeatReport`/`ServiceInfo` um `memoryUsedMb`, `memoryMaxMb`,
  `cpuPercent` erweitert (defaulted `-1` → wire-kompatibel mit alten Bridges).
- Die Services-Tabelle im Panel zeigt **RAM** (used/max mit Auslastungsbalken)
  und **CPU %** pro Service.

## 0.34.0 — 2026-07-22

### Datei-Manager
- Neue Panel-Seite **Files** (Permission `helix.panel.files`): Workspace- und
  Template-Dateien browsen (Breadcrumb), Textdateien direkt im Panel
  editieren (bis 1 MiB) und Dateien/Ordner löschen.
- Roots: `static:<serviceId>`, `temp:<serviceId>`, `template:<name>`. Alle
  Pfade sind strikt auf ihren Root begrenzt — Traversal-Versuche (`..`,
  manipulierte Root-Ids) werden mit `400` abgelehnt (getestet).
- Schreib- und Löschoperationen landen im Audit-Log.
- Routen: `GET /files/roots|list|content`, `PUT /files/content`,
  `DELETE /files`.

## 0.33.0 — 2026-07-22

### Backups & Snapshots
- Statische Service-Workspaces lassen sich als Zip sichern
  (`Helix/backups/<serviceId>/<timestamp>.zip`), Retention 10 pro Service.
- **Restore** spielt ein Archiv in den (gestoppten) Workspace zurück und
  ersetzt dessen Inhalt; Zip-Slip-geschützt. Restore ist blockiert, solange
  der Service läuft.
- Neue Panel-Seite **Backups** (Permission `helix.panel.backups`): Erstellen,
  Wiederherstellen, Löschen; laufende Services sind markiert.
- Actions `backup.create/list/restore/delete` — damit per **Scheduler**
  automatisierbar (z.B. Job `backup.create Lobby-1` täglich 04:00).
- Routen: `GET /backups`, `POST /services/{id}/backups`,
  `POST /backups/{serviceId}/{file}/restore`, `DELETE /backups/{serviceId}/{file}`.

## 0.32.3 — 2026-07-22

### Fix: Dashboard-Updates ohne Force-Reload
- Nach Node-Updates zeigte der Browser teils das alte Dashboard, bis man
  force-reloadete: die `index.html` wurde ohne Cache-Header ausgeliefert und
  referenzierte ein veraltetes Bundle.
- Jetzt: `Cache-Control: no-cache` für die Seite (revalidiert bei jedem
  Aufruf) und `immutable` (1 Jahr) für die content-gehashten Assets — Updates
  erscheinen sofort, Assets bleiben maximal gecacht.

## 0.32.2 — 2026-07-22

### Fix: First-Start generiert vollständige Configs
- Die beim ersten Start erzeugte `config/node.toml` war auf dem Stand von
  v0.1 (nur `[control]` mit host/port/token). Jetzt wird sie **vollständig
  und kommentiert** generiert: Login/TLS-Keys, `[docker]`, `[storage]`
  (json/postgres/mongodb) und `[network]`.
- Neuer Drift-Schutz-Test: die generierte Datei muss beim Laden exakt die
  Code-Defaults ergeben — das Template kann nicht mehr unbemerkt veralten.
- `versions.toml`-Template dokumentiert jetzt den optionalen `url`-Override.
- Alle übrigen Generatoren geprüft und vollständig: Task-TOMLs (alle Felder,
  Roundtrip-getestet), `wrapper.properties`, Paper-Defaults, `velocity.toml`.

## 0.32.1 — 2026-07-22

### Netzwerk-Name im Panel editierbar
- Der `{network}`-Placeholder war bisher nur über `node.toml` setzbar und beim
  Boot eingefroren. Jetzt ist der Netzwerk-Name — wie der Prefix — im
  Dashboard unter **Settings → Network** editierbar (persistiert, Änderung
  greift sofort; `node.toml` liefert nur noch den Startwert).
- Sofortige Propagation: Namensänderung weckt die Proxies (Routing-Snapshot),
  zusätzlich `network.name` als Bridge-Value; `{network}` funktioniert damit
  auch in der Tablist auf Paper.

## 0.32.0 — 2026-07-22

### Globaler Prefix & MiniMessage überall
- Neuer **globaler Netzwerk-Prefix**, einstellbar im Dashboard unter
  **Settings → Network prefix** (persistiert, Änderung greift sofort).
- Als `{prefix}`-Placeholder **überall** nutzbar: in jeder konfigurierbaren
  Addon-Nachricht (automatisch über `Messages.format`), in MOTD, Tablist und
  allen Disconnect-Screens (Bridge-Kontext + Bridge-Value `network.prefix`).
  Im Chat-Format bleibt `{prefix}` der Rang-Prefix des Spielers.
- **MiniMessage einheitlich**: Auch die Paper-Bridge rendert jetzt alle Texte
  (Chat-Format, Tablist, Player-Command-Antworten auf Velocity) über
  MiniMessage — Gradienten/Hex funktionieren damit in allen Nachrichten;
  Legacy-`&`-Codes werden weiterhin übersetzt (geteilter `LegacyToMini` in
  helix-api). Spieler-Chatnachrichten werden escaped, damit niemand
  MiniMessage-Tags (z.B. Click-Events) einschleusen kann.

## 0.31.0 — 2026-07-22

### Animierte Tablist & animierte MOTD
- **Tablist**: Header/Footer bestehen jetzt aus beliebig vielen **Frames**
  (max. 20) mit konfigurierbarem Intervall (min. 250 ms). Die Paper-Bridge
  animiert zeitbasiert (eigener 250-ms-Task, wendet nur bei Frame-Wechsel an);
  statische Tablists verhalten sich wie bisher. Neue Action
  `tablist.import <json>`; Panel mit Frame-Editor und **animierter Preview**.
- **MOTD**: Beide Profile (normal/maintenance) unterstützen **Frames** für die
  beiden Zeilen (max. 20, Intervall min. 500 ms). Die Velocity-Bridge wählt
  beim Server-List-Ping zeitbasiert den aktiven Frame — wiederholtes
  Refreshen der Serverliste zeigt die Animation. Neue Action
  `motd.import <json>`; Panel mit Frame-Editor pro Profil und animierter
  Preview.
- Publiziert als `tablist.config`/`motd.config`; alte Bridges/Configs bleiben
  kompatibel (Basisfelder = Frame 1).

## 0.30.0 — 2026-07-22

### MOTD-Addon
- Neues Addon `helix-addon-motd`: Server-Liste (MOTD) mit zwei Profilen —
  **normal** und **maintenance**. Bei aktiver Netzwerk-Wartung wird
  automatisch das Wartungs-Profil ausgeliefert.
- Pro Profil einstellbar (alles über die neue Panel-Seite **MOTD** mit
  Live-Preview): beide Zeilen (MiniMessage/`&`-Codes, Platzhalter `{online}`
  `{max}` `{network}`), angezeigte Online-/Max-Spielerzahl (`-1` = echte
  Werte), Version-Text und die Hover-Zeilen der Spielerzahl.
- Actions: `motd.set <normal|maintenance> <field> <value...>`, `motd.show`,
  `motd.export`. Publiziert als Bridge-Value `motd.config`.
- Die Velocity-Bridge pollt jetzt auch Bridge-Values (5s-Heartbeat) und
  rendert das passende Profil bei jedem Server-List-Ping; ohne MOTD-Addon
  bleibt das bisherige Minimal-Verhalten.

## 0.29.1 — 2026-07-22

### Fix: Player-Manager im Permissions-Panel
- Der Player-Manager crashte für jeden User **ohne permanente Gruppen** (der
  häufigste Fall: jemand hat nur eine Permission): der Export ließ leere
  Felder weg (`encodeDefaults=false`), das Panel griff ungeschützt auf
  `u.groups` zu → TypeError, die Detail-Ansicht blieb leer.
- Doppelt behoben: das Panel normalisiert geladene User (alle Listen existieren
  immer — funktioniert damit auch gegen alte Addon-Builds), und der Addon-
  Export schreibt jetzt alle Felder (`encodeDefaults = true`).

## 0.29.0 — 2026-07-22

### Permission-Katalog & temporäre Grants
- `addon.json` hat ein neues Feld `permissions`: alle Permission-Nodes, die
  ein Addon implementiert (deklariert für bans, moderation, teamutils,
  permissions).
- Das **Permissions-Addon** baut daraus den netzwerkweiten Katalog: es
  aggregiert die Manifeste aller installierten Addons, die Core-Permissions
  der Node und scannt die `plugin.yml` aller Backend-Plugins in den
  Service-Workspaces/Templates (neue Context-APIs `installedAddons()`,
  `corePermissions()`, `serviceDirectories()`; Action `perm.catalog`).
- Im Permissions-Panel sind Grants jetzt **auswählbar** (Katalog-Vorschläge
  mit Quelle) — ein Freitext-Feld bleibt für unbekannte Nodes erhalten.
- **Temporäre Vergaben**: persönliche Permissions und Gruppen-Mitgliedschaften
  können mit Ablauf vergeben werden (`perm.user.grant <player> <node> [7d]`,
  `perm.user.addgroup <player> <group> [12h]`); abgelaufene Grants werden bei
  der Auflösung ignoriert und automatisch aufgeräumt. Das Panel zeigt
  Restlaufzeiten und bietet Dauer-Felder.

## 0.28.0 — 2026-07-22

### API-Performance-Metriken im Dashboard
- Die Node misst jede Control-API-Antwortzeit und liefert über `GET /api-stats`
  ein rollierendes Fenster: **Ø-Antwortzeit**, **p95**, **Requests/min** und
  **Fehlerrate**. Die durchschnittliche Antwortzeit fließt zusätzlich als
  `avgApiMs` in die Metrik-Zeitreihe.
- Die Overview-Seite zeigt jetzt eine **API-performance**-Karte (avg/p95/
  req-min/error-rate) und einen Latenz-Trendgraphen.

### Permission-Panel: User-Verwaltung & Gruppen-Modal
- Das Permissions-Panel listet nun **alle Users** mit ihren Gruppen und
  Permission-Zählern; „Manage" öffnet den Editor (Gruppen zuweisen/entfernen,
  persönliche Permissions grant/revoke).
- Gruppen werden über ein **Modal** angelegt (Name, Weight, Default-Flag) statt
  über `prompt()`. Panels haben dafür wiederverwendbare Modal-Styles.

## 0.27.3 — 2026-07-22

### Fix: Dashboard-Build (`buildDashboard`) bricht ohne TTY ab
- Der Gradle-Frontend-Build scheiterte in manchen Umgebungen mit
  `ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY` — pnpm wollte `node_modules`
  neu aufsetzen und konnte ohne TTY nicht nachfragen.
- Behoben mit pnpms eigenen, dokumentierten Remedien: `CI=true` im
  `buildDashboard`-Exec und `confirmModulesPurge: false` in
  `helix-dashboard/pnpm-workspace.yaml` (voll non-interaktiv).

## 0.27.2 — 2026-07-22

### Fix: Web-Konsole auch für Docker-Services
- Die interaktive Konsole funktionierte nur für Prozess-Services (stdin des
  Wrapper-Prozesses). Docker-Container haben keinen erreichbaren stdin.
- Neuer, einheitlicher Weg: der Wrapper liest Konsolenbefehle aus der Datei
  `console.in` im Workspace und leitet sie an den Server-stdin weiter
  (`ConsoleForwarder`). Beide Executors hängen Befehle an diese Datei an; da der
  Docker-Workspace bind-gemountet ist (`/helix`), funktioniert das ohne
  `docker exec`/`attach`. Konsole läuft jetzt identisch für Prozess **und**
  Docker.

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
