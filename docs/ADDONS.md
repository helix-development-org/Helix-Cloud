# Addons entwickeln

Addons erweitern die Node um eigene Actions und Logik. Sie werden als
`.hxa` Datei in `Helix/addons/` installiert und beim Node-Start geladen —
mit eigenem Classloader und sauberem Lifecycle.

## HXA-Format

Eine `.hxa` Datei ist ein Zip mit genau zwei Einträgen:

```text
my-addon.hxa
├── addon.json   # Manifest
└── addon.jar    # kompilierte Addon-Klassen
```

`addon.json`:

```json
{
  "id": "my.addon",
  "name": "My Addon",
  "version": "1.0.0",
  "main": "org.example.MyAddon",
  "description": "What it does.",
  "permissions": ["my.addon.use", "my.addon.admin"]
}
```

`permissions` listet **alle Permission-Nodes, die das Addon implementiert**.
Sie fließen in den netzwerkweiten Permission-Katalog: das Permissions-Addon
aggregiert die Manifeste aller installierten Addons, die Core-Permissions der
Node (`context.corePermissions()`) und die `plugin.yml` aller Backend-Plugins
in den Service-Workspaces/Templates (`context.serviceDirectories()`), und
bietet sie im Panel als auswählbare Grants an (Action `perm.catalog`).
Unbekannte Nodes lassen sich weiterhin frei eintippen. Grants können
**temporär** vergeben werden (`perm.user.grant <player> <node> [30m|12h|7d]`,
ebenso `perm.user.addgroup`).

## Minimales Addon

Abhängigkeit: `helix-addon-sdk` (bringt `helix-api` mit). Beide Module
stellt die Node zur Laufzeit bereit — **nicht** ins `addon.jar` einpacken.

```kotlin
package org.example

import org.helix.addon.sdk.AddonBase
import org.helix.api.action.ActionResult

/** Registers a single ping action. */
class MyAddon : AddonBase() {
    override fun enable() {
        action("my.ping", "Replies with pong.") {
            ActionResult.ok("pong")
        }
    }

    override fun onDisable() {
        // optional cleanup; registered actions are removed automatically
    }
}
```

Über `context` steht mehr bereit:

- `context.actions.invoke(...)` — jede Platform-Action aufrufen
  (`service.start`, `player.kick`, `proxy.maintenance`, …)
- `context.dataDirectory` — eigener Persistenz-Ordner
  (`Helix/addons/data/<id>/`)
- `context.registerJoinGate { request -> ... }` — Join-Versuche prüfen:
  die Velocity-Bridge fragt bei jedem Login generisch die Node, die Node
  wertet alle registrierten Gates aus (erste Ablehnung gewinnt, Gates mit
  Exception werden übersprungen). Gates werden beim Disable automatisch
  entfernt.

```kotlin
context.registerJoinGate { request ->
    if (isBanned(request.name)) JoinDecision.deny("You are banned.")
    else JoinDecision.allow()
}
```

- `context.registerPermissionResolver { request -> ... }` — Permission-
  Fragen beantworten. **Permissions hängen nicht vom Addon ab:** die Node
  löst jede Frage über den `PermissionService` auf. Ist **kein** Resolver
  registriert, entscheidet der Default — das **native MC-Permission-System**
  (die Proxy-Bridge wertet die relevanten Nodes beim Join via
  `player.hasPermission` aus und meldet sie). Sobald ein Addon einen Resolver
  registriert, **überschreibt** es den Default vollständig und ist allein
  maßgeblich. Damit läuft das gesamte Netzwerk auch ohne Permission-Addon.

```kotlin
context.registerPermissionResolver { request ->
    hasPermission(request.name, request.permission)
}
```

- `context.hasPermission(player, permission)` — fragt den `PermissionService`
  (Addon-Resolver falls vorhanden, sonst nativ).
- `context.onlinePlayers()` — alle Spieler im Netzwerk (die Proxies melden
  Join/Leave an die Node).
- `context.registerPlayerListener(listener)` — netzwerkweite
  Join/Leave-Events empfangen.
- `context.registerDisplayResolver { name -> ... }` — eine Komponente des
  Display-Namens (`prefix + color + name + suffix`) liefern. Die Node
  merged alle Resolver komponentenweise (erster nicht-leerer Wert pro
  Komponente gewinnt); Konvention: Prefix = Gruppen (Chat-Addon), Name =
  Nick (Nick-Addon), Suffix = Clan-Tag (Clan-Addon). Das Ergebnis rendert
  die Paper-Bridge in Chat, Tablist und Nametag.
- `context.publishBridgeValue(key, value)` — globale Werte publizieren,
  die die Bridges pollen (`tablist.header`, `tablist.footer`,
  `chat.format`).
- `context.publishNotification(category, message)` /
  `context.registerNotificationListener { category, message -> ... }` —
  entkoppelter Notification-Bus zwischen Addons. Konvention: Bans, Warns
  und Kicks werden unter der Kategorie `moderation` publiziert; das
  Team-Utils-Addon leitet diese an Online-Teammitglieder weiter, ohne
  dass sich die Addons kennen.

## Spieler-Commands

Eine Action mit `playerCommand = true` wird von der Velocity-Bridge
automatisch als In-Game-Command registriert. Der Handler bekommt den
Spielernamen als erstes Argument; `permission` gated den Command:

```kotlin
context.registerAction(
    ActionDescriptor(
        name = "kick",                      // → /kick im Spiel
        description = "Kicks a player.",
        usage = "kick <player> [reason...]",
        playerCommand = true,
        permission = "helix.mod.kick",      // null = jeder darf
    ),
) { invocation ->
    val executor = invocation.arguments.first()   // ausführender Spieler
    val args = invocation.arguments.drop(1)       // getippte Argumente
    ...
}
```

Antwort-Zeilen (`ActionResult.lines`, `&`-Farbcodes erlaubt) werden dem
Spieler in den Chat geschrieben. Nachrichten an andere Spieler laufen über
die generischen Actions `player.message <player> <text...>` und
`player.broadcast <text...>`.

Wer von `AddonBase` erbt, nutzt kurz `action(name, desc, usage,
playerCommand = true, permission = "…") { … }`. Ein bewährtes Muster ist ein
**Dispatcher-Command**, der Subcommands auf die eigenen `punkt.getrennten`
Actions abbildet (so werden `/bans`, `/permissions` gebaut):

```kotlin
action("bans", "Manage bans.", "bans <set|pardon|list> …",
    playerCommand = true, permission = "helix.bans") { inv ->
    val args = inv.arguments.drop(1)            // ohne Spielernamen
    when (args.firstOrNull()) {
        "set"    -> context.actions.invoke(ActionInvocation("ban.set", args.drop(1)))
        "pardon" -> context.actions.invoke(ActionInvocation("ban.pardon", args.drop(1)))
        else     -> ActionResult.ok("/bans set … | /bans pardon …")
    }
}
```

### Eingebaute In-Game-Commands

- `/helix <addons|enable|disable|reload> [id]` (Permission `helix.admin`) —
  Addons anzeigen, aktivieren, deaktivieren, neue `.hxa` live nachladen.
- `/bans …` (Permission `helix.bans`) — aus dem Bans-Addon.
- `/permissions …` (Permission `helix.permissions`) — aus dem Permissions-Addon.

Die Permission jedes Player-Commands wird auch nativ ausgewertet
(`GET /internal/permission-nodes`), d.h. OPs/LuckPerms erhalten die Commands
auch ohne Permission-Addon.

## Packen mit Gradle

```kotlin
val packageHxa by tasks.registering(Zip::class) {
    dependsOn(tasks.jar)
    archiveFileName.set("my-addon-1.0.0.hxa")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.projectDirectory.file("src/main/resources/addon.json"))
    from(tasks.jar.flatMap { it.archiveFile }) { rename { "addon.jar" } }
}
```

## Java-Addons

Die komplette Addon-API ist auch aus reinem Java nutzbar (kein Kotlin
nötig). Factory-Methoden sind `@JvmStatic` (`ActionResult.ok(...)`,
`JoinDecision.deny(...)`), Konstruktoren mit Defaults sind
`@JvmOverloads` (z.B. `new ActionDescriptor(name, desc, usage)`),
Handler sind SAM-Interfaces (Java-Lambdas), und `Messages.format` hat
eine `Map`-Überladung.

```java
public class MyAddon implements HelixAddon {
    @Override public void onEnable(AddonContext ctx) {
        Messages msg = ctx.messages(java.util.Map.of("greet", "&aHi {name}!"));
        ctx.registerAction(
            new ActionDescriptor("my.greet", "Greets", "my.greet <name>"),
            inv -> ActionResult.ok(msg.format("greet",
                java.util.Map.of("name", inv.getArguments().get(0))))
        );
    }
}
```

Referenz-Implementierungen in diesem Repo:

- `helix-addon-example` — minimales Addon (Actions, Action-Aufrufe)
- `helix-addon-bans` — vollständiges Produkt-Addon: persistente Bans mit
  Ablauf (`7d`, `12h`), Join-Gate, Kick über die generische
  `player.kick`-Action. Die Bridges enthalten null Ban-spezifischen Code.
- `helix-addon-permissions` — Permission-System: Gruppen mit Gewicht,
  Vererbung und Default-Flag, persönliche Permissions, Wildcards
  (`*`, `prefix.*`) und Negation (`-node`). Registriert einen
  PermissionResolver; damit funktioniert u.a. `helix.maintenance.bypass`
  an der Velocity-Bridge. Actions: `perm.group.*`, `perm.user.*`,
  `perm.check`.
- `helix-addon-friends` — `/friend add|accept|deny|remove|list|requests`,
  Join-Benachrichtigungen an Online-Freunde.
- `helix-addon-tablist` — konfigurierbarer Tablist-Header/-Footer
  (`tablist.header`, `tablist.footer`, `tablist.show`), Platzhalter
  `{online}`/`{max}`.
- `helix-addon-chat` — Chat-Zeilenformat (`chat.format`), gerendert von der
  Paper-Bridge. Die Display-Komponenten kommen aus den anderen Addons:
  `{prefix}`/`{color}` aus der Permission-**Gruppe** des Spielers
  (`perm.group.prefix`/`perm.group.color`, höchstes Gewicht gewinnt —
  bewusst unabhängig von Permission-Nodes, eine `*`-Permission ändert die
  Anzeige nicht), `{name}` aus dem Nick-Addon, `{suffix}` aus dem
  Clan-Addon. Das aufgelöste Display-Profil färbt zusätzlich den **Namen
  über dem Kopf** (per Scoreboard-Team) und den **Tablist-Namen**.
- `helix-addon-economy` — Coins mit `/balance` und `/pay` sowie
  `eco.give/take/set/get`.
- `helix-addon-moderation` — permission-gated `/kick`, `/warn`, `/warns`,
  `/announce`, `/tempban` (delegiert an das Ban-Addon) mit Warn-Historie.
- `helix-addon-teamutils` — Teamchat `/tc`, `/team` (Online-Teammitglieder),
  Join/Leave-Notifications fürs Team, `team.notify` für CLI/Dashboard und
  Live-Weiterleitung aller `moderation`-Notifications (Bans, Warns, Kicks)
  an das Team. Teammitglied = Permission `helix.team.member`.
- `helix-addon-discord` — Discord-Bot auf Kord-Basis (Gateway, im HXA
  gebündelt). Leitet `moderation`-Notifications in einen Discord-Channel
  weiter und beantwortet dort `!status`, `!players`, `!help` und
  `!run <action>` (nur für konfigurierte Admin-User-IDs). Konfiguration in
  `Helix/addons/data/helix.discord/discord.json` (`botToken`, `channelId`,
  `commandPrefix`, `notificationCategories`, `adminUserIds`), danach
  `discord.reload`. Weitere Actions: `discord.status`, `discord.send`.
  Benötigte Bot-Intents im Discord Developer Portal: *Message Content*.
- `helix-addon-motd` — Server-Liste (MOTD) mit zwei Profilen: **normal** und
  **maintenance** (bei aktiver Netzwerk-Wartung automatisch). Einstellbar
  pro Profil: beide Zeilen (MiniMessage/`&`-Codes, `{online}` `{max}`
  `{network}`), angezeigte Online-/Max-Spielerzahl (`-1` = echt),
  Version-Text und Hover-Zeilen der Spielerzahl. Actions `motd.set`,
  `motd.show`, `motd.export`; Panel-Seite **MOTD** mit Live-Preview.
  Gerendert von der Velocity-Bridge beim Server-List-Ping.
- `helix-addon-stats` — generisches Stats-/Leaderboard-System: `stats.add/
  set/get` für beliebige numerische Stat-Keys (kein Enum, jeder Addon-/Paper-
  Plugin-Aufrufer definiert seine eigenen Keys), `stats.top <stat> [limit]`
  für sortierte Top-N-Leaderboards, `/stats <stat> [player]` bzw.
  `/stats top <stat> [limit]` im Spiel. `stats.season.reset <stat>`
  archiviert die aktuellen Platzierungen (nie destruktiv) und setzt den
  Live-Stat zurück — gedacht zum Verdrahten in einen periodischen Job
  (`POST /jobs`); vergangene Saisons bleiben über `stats.season.list`/
  `stats.season.view <stat> <season>` einsehbar.
- `helix-addon-parties` — leichtgewichtige, netzwerkweite Gruppen unterhalb
  von Clans: Leader + Mitgliederliste, rein **im Speicher** (kein
  Restart-Persistenz, kein Bank/Tag), gedacht für Queueing/Matchmaking.
  `/party create|invite|accept|leave|kick|list`; verlässt der Leader die
  Party, übernimmt das dienstälteste verbleibende Mitglied. `party.members
  <player>` löst die aktuelle Gruppe eines Spielers auf (ohne Party = Party
  aus einer Person) für andere Addons/Plugins.
- `helix-addon-maprotation` — node-koordinierte Map-/Welt-Rotationen:
  `maprotation.configure <id> <map1,map2,...>` legt eine benannte,
  geordnete Liste an; `maprotation.advance <id>` schaltet weiter (per
  Scheduler-Job oder direkt bei Rundenende aufgerufen) und broadcastet die
  Änderung über die `maprotation`-Notification-Kategorie,
  `maprotation.current`/`maprotation.next` zeigen den Stand. Reine
  Orchestrierung — das tatsächliche Weltladen/Teleportieren übernimmt eine
  Paper-seitige Bridge-Komponente oder das eigene Plugin des Servers.
- `helix-addon-profile` (+ `helix-addon-profile-paper`) — zentrales
  Spielerprofil: aggregiert, was beliebige andere Addons über
  `AddonContext.registerProfileInfoProvider`/`registerProfileSettingProvider`
  beisteuern (Statistiken read-only, interaktive Einstellungen wie
  Cosmetics/Subtitles/Sprache als Toggle/Choice/FreeText), ohne dass diese
  Addons sich kennen. Speichert den tatsächlich gewählten Wert selbst
  zentral (`profile.setting.set`, gated; `profile.setting.admin-set` für
  Staff-Override; `profile.setting.clear`). `/profile` ist ein
  netzwerkweiter Text-Fallback über den node-geroutet Player-Command;
  `/profilemenu` (Paper-seitig, IGui-basiert) öffnet ein grafisches Menü —
  beide Namen sind bewusst getrennt, da Velocity `/profile` bereits als
  Proxy-Befehl abfängt, bevor ein Paper-Plugin ihn sehen könnte.
  Dashboard-Panel **Profiles** erlaubt Staff lesenden Zugriff plus
  manuelles Setzen/Zurücksetzen. Die IGui-Textur-Datenbank des Paper-Menüs
  (`NodeGuiTextureDatabase`) läuft über neue `profile.texture.*`-Actions
  statt einer direkten DB-Verbindung vom Spielserver aus — konsistent mit
  jeder anderen Storage-Anbindung in diesem Projekt. Das Menü selbst bündelt
  ein eigenes `pack.zip` (`helix_profile`-Namespace, nur Fonts) für den
  Titel, der IGuis `centeredText`/`SpacingRenderer` nutzt — analog zu
  Guards/BetterMsgs' eigenen Pack-Generatoren.
- `helix-addon-subtitles` (+ `helix-addon-subtitles-paper`) — eine zweite
  Anzeigezeile unter dem Namen, gewählt über das Profile-System: eine
  Betreiber-vordefinierte Liste (`subtitle.config.add/remove/list`, pro
  Eintrag optional permission-gated) plus optional ein permission-gated
  Freitext, der die Listenauswahl überschreibt. Kein eigener Wertespeicher
  — dieses Addon liest/schreibt ausschließlich über
  `profile.setting.get`/die Profile-Erweiterungsschnittstelle und
  publiziert den effektiven Text als `subtitle.text.<spieler>`
  Bridge-Value. Paper-seitig rendert eine dem Spieler folgende, nicht
  persistente Text-Display-Entity knapp unter dem Namensschild (kein
  Passagier-Trick, um echtes Reiten nicht zu stören).
- `helix-addon-cosmetics` (+ `helix-addon-cosmetics-paper`) — anziehbare
  Flügel und Kopfbedeckungen (je 6, rang-/permission-gated), gewählt über
  das Profile-System als zwei Choice-Settings (`wings`/`headwear`). Jedes
  Cosmetic ist ein `CustomModelData`-getaggter `PAPER`-Carrier mit eigenem
  handgeschriebenen Item-Modell (Flügel: zwei flache Panels; Kronen: Band
  + vier Ecken-Zacken; Halos: flacher quadratischer Ring) und
  Java2D-gezeichneter Textur, zur Build-Zeit generiert
  (`:helix-addon-cosmetics-paper:generatePack`) — genau wie bei BetterMSGs,
  nur mit echten Item-Modellen statt Font-Glyphen. Paper-seitig rendert
  eine Item-Display-Entity pro Slot, jeden Tick neu positioniert/rotiert
  nach Spieler-Blickrichtung — kein Rüstungs-/Elytra-Slot wird belegt,
  also keine Kollision mit echter Ausrüstung. `CustomModelData`-Werte sind
  bewusst als fester Katalog im Code hinterlegt (nicht zur Laufzeit
  konfigurierbar), da die Modelle selbst zur Build-Zeit im Resource-Pack
  gebacken werden. Der Carrier definiert sein Modell **zweifach**:
  `assets/minecraft/models/item/paper.json` (klassisches Overrides-Array,
  für ältere/ViaVersion-gebrückte Clients) UND
  `assets/minecraft/items/paper.json` (moderner `range_dispatch` über
  `minecraft:custom_model_data`, seit dem Item-Components-Rework nötig,
  da neuere Clients sonst ihr eingebautes Item-Modell statt des
  Overrides-Arrays verwenden und der Carrier als schlichtes Papier
  rendert).

## Lifecycle

| Zustand | Bedeutung |
|---|---|
| `ENABLED` | geladen, Actions registriert |
| `DISABLED` | geladen, Actions entfernt, Classloader geschlossen |
| `FAILED` | Laden/Aktivieren fehlgeschlagen (Node läuft weiter) |

Verwaltung über CLI/REST/Dashboard: `addon.list`, `addon.enable <id>`,
`addon.disable <id>`.

## Daten speichern (JSON oder Postgres)

Addons speichern über `context.storage()` — einen Dokument-Store, dessen
Backend der Node zentral wählt (`node.toml [storage] mode`). Im
`json`-Modus wird pro Key eine Datei im Addon-Datenordner geschrieben, im
`postgres`-Modus landet alles in der geteilten DB-Tabelle. Der Addon-Code
ist in beiden Fällen identisch.

```kotlin
class MyStore(private val storage: org.helix.api.storage.AddonStorage) {
    fun load(): List<Entry> =
        storage.read("entries")?.let { Json.decodeFromString(it) } ?: emptyList()
    fun save(entries: List<Entry>) =
        storage.write("entries", Json.encodeToString(entries))
}

// im Addon:
val store = MyStore(context.storage())
```

`AddonStorage` bietet `read(key)`, `write(key, value)`, `delete(key)` und
`keys()`. Für Tests gibt es `InMemoryAddonStorage`.

## Pro-Task-Aktivierung

Jeder Task kann Addons einzeln abschalten (`disabledAddons` im Task,
editierbar über die Task-Detailseite im Dashboard). Addons mit
service-spezifischem Verhalten sollten das respektieren:

- `context.isActiveForTask(taskName)` — ob dieses Addon für den Task aktiv
  ist.
- Bridge-Values (`chat.format`, `tablist.header` …) werden automatisch pro
  Service gefiltert: ein Service bekommt nur Werte von Addons, die für
  seinen Task aktiv sind. Ein Task mit `disabledAddons = ["helix.chat"]`
  bekommt also kein Chat-Format, während der Rest normal läuft.

Netzwerkweite Addons (z.B. Bans) ignorieren die Task-Aktivierung.

## Konfigurierbare Nachrichten (mehrsprachig)

Alle player-facing Texte eines Addons sollen einstellbar und übersetzbar
sein. Dafür deklariert das Addon Default-Templates **pro Sprache**; die
Node persistiert Custom-Werte im Addon-Storage und macht alles über die
Dashboard-Seite **Translations** editierbar (flache Keys
`helix.translations.<addon-id>.<key>`). Änderungen wirken sofort (kein
Neustart). Templates nutzen `{platzhalter}`, MiniMessage und `&`-Farbcodes.

Spieler wählen ihre Sprache mit `/helix language <code>`; beim First Join
wird die Minecraft-Client-Sprache übernommen. Nachrichten an einen
konkreten Spieler laufen deshalb über `formatFor(player, key, …)` — nur
Texte ohne Empfänger (z.B. Konsole) über `format(key, …)`.

```kotlin
class MyAddon : AddonBase() {
    private lateinit var msg: org.helix.api.message.Messages
    override fun enable() {
        msg = context.localizedMessages(mapOf(
            "en" to mapOf("welcome" to "&aWelcome, &f{player}&a!"),
            "de" to mapOf("welcome" to "&aWillkommen, &f{player}&a!"),
        ))
        // ...
        val text = msg.formatFor(name, "welcome", "player" to name)  // Sprache des Spielers
    }
}
```

`context.messages(defaults)` (einsprachig) bleibt als Kurzform erhalten und
registriert die Defaults als Englisch. Fehlende Übersetzungen fallen auf
die Default-Sprache des Netzwerks zurück. Über die REST-API:
`GET /translations`, `POST /translations {key, language, value}` bzw.
`{key, language, reset:true}`.

## Paper-Komponenten & Resource Packs

Ein HXA kann neben `addon.json` + `addon.jar` zwei optionale Einträge
enthalten:

- **`paper.jar`** — ein vollständiges Paper-Plugin. Die Node installiert es
  beim Service-Start als `plugins/HelixAddon-<id>.jar` in jeden
  Paper-Workspace, in dem das Addon aktiv ist (Task-`disabledAddons` wird
  respektiert; deaktivierte Addons verschwinden beim nächsten Start).
  Die Komponente erreicht die Node über die Env-Variablen
  `HELIX_CONTROL_URL`/`HELIX_CONTROL_TOKEN` (wie die Bridge) und ruft
  Addon-Actions über `POST /actions` auf — so bleibt die Node-Seite die
  einzige Datenquelle (netzwerkweit), während die GUI-/Event-Logik im
  Server läuft.
- **`velocity.jar`** — analog für Velocity-Services (z.B. netzwerkweite
  Enforcement-Layer wie bei Helix-Guard).
- **`paper/<name>.jar`** — weitere Paper-Plugins, die als Abhängigkeiten
  mitreisen (z.B. packetevents für Helix-Guard); installiert als
  `HelixAddon-<id>-<name>.jar`.
- **`pack.zip`** — ein Resource Pack. Die Node fügt die Packs **aller
  aktivierten Addons** zu einem einzigen Netzwerk-Pack zusammen, das die
  Control-API öffentlich unter `/api/v1/packs/network.zip` (+ `.sha1`)
  ausliefert; die Velocity-Bridge verteilt es beim Proxy-Join automatisch
  an jeden Spieler (und erneut, sobald sich das Pack ändert). Die
  `pack.mcmeta` wird dabei generiert; liefern zwei Addons denselben
  Eintragspfad, gewinnt das (nach Addon-Id sortiert) erste Addon und ein
  Warn-Log nennt beide. Ist die Control-API nicht unter der automatisch
  ermittelten Adresse erreichbar, setzt die Action
  `network.packurl <url|->` (oder die Env-Variable `HELIX_PACK_URL` am
  Proxy) die Download-URL. Die Einzel-Packs bleiben zusätzlich unter
  `/api/v1/packs/<addon-id>.zip` abrufbar.

Referenz-Implementierung: **BetterMSGs** (`helix-addon-bettermsgs` +
`helix-addon-bettermsgs-paper`) — ein Handy-artiges `/msg`-GUI auf Basis
der IGui-Library (Modul `helix-gui`, vendored im Monorepo), dessen Texturen zur Buildzeit
mit Java2D gezeichnet werden (`:helix-addon-bettermsgs-paper:generatePack`).

### Framework: INpc (paketbasierte NPCs)

**Helix-NPC** (`helix-addon-npc` + `helix-addon-npc-paper`) zeigt, wie eine
Paper-Komponente das **INpc**-Framework wiederverwendet. INpc ist Tytoss'
Kotlin-Bibliothek für vollständig konfigurierbare, paketbasierte Spieler-NPCs
(Skin, Hologramm, Equipment, Look-Behavior, Interaktion) — das NPC-Gegenstück
zu IGui.

INpc wird als **vorgebautes Jar vendored**, nicht als Composite-Build: sein
eigener Build braucht `paperweight.userdev` + `paperDevBundle` (Mojang-mapped
NMS) + JVM-Toolchain 25, was den Helix-Build unnötig belasten würde. Stattdessen
liegt das Library-Jar unter `libs/` und wird mitsamt seinen Runtime-Deps ins
`paper.jar` gepackt:

```kotlin
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation(files("libs/inpc-1.0.0-SNAPSHOT.jar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
```

```kotlin
val npcs = INpc.install(this)
npcs.npc("greeter") {
    location(world, x, y, z, yaw, pitch)
    skin("Notch")            // oder skin { mirrorViewer() } für den Skin des Viewers
    hologram { line("<gold>Hi") }
    look { nearestPlayer() }
    onInteract { ctx -> ctx.player.performCommand("server Lobby") }
}.spawn()
```

Das Reobf-Jar referenziert NMS über Mojang-Namen — das ist für Paper 1.21.11
korrekt (Mojang-mapped Runtime). Da das Jar mit JVM-24-Bytecode ausgeliefert
wird, müssen die Paper-Server auf **Java 24+** laufen (Helix-Node-Toolchain 24,
Deployment hier Java 25). Andere Addons können denselben Weg gehen und INpc so
als geteiltes Framework nutzen.

## Eigene Dashboard-Seite beisteuern

Ein Addon kann eine eigene Seite ins Webpanel bringen. Die Seite ist ein
HTML-Fragment (mit `<style>`/`<script>`), das das Dashboard in einem
**sandboxed iframe** rendert. Der Control-Token bleibt im Host — Panel-Code
kann ihn nicht lesen. Actions ruft die Seite über die injizierte Brücke
`Helix.action(name, ...args)` auf (Promise auf `{success, lines}`), plus
`Helix.toast(msg)` und `Helix.ready(cb)`. CSS-Variablen des Dashboards
(`--accent`, `--surface`, …) und Basisklassen (`.card`, `.btn`, `.badge`,
`table`) stehen bereit, damit Panels nativ aussehen.

```kotlin
class MyAddon : AddonBase() {
    override fun enable() {
        action("my.export", "Data for the panel.") { ActionResult.ok(json) }
        panel(id = "my", title = "My Page", resource = "/panel.html")
    }
}
```

`panel.html` (im `resources/` des Addons):

```html
<div class="card"><div class="card-head"><h2>My Page</h2></div>
  <div class="card-body" id="out">loading…</div></div>
<script>
  Helix.ready(async () => {
    const r = await Helix.action("my.export");
    document.getElementById("out").textContent = r.lines[0];
  });
</script>
```

Das Panel erscheint in der Sidebar unter **Extensions** und verschwindet
wieder, wenn das Addon deaktiviert wird. Mitgelieferte Panels:
Permissions, Clans, Economy, Bans, Chat, Tablist, Discord, MOTD.

## Addons zur Laufzeit nachladen

Neue `.hxa` Dateien lassen sich ohne Node-Neustart laden: einfach in
`Helix/addons/` ablegen und `addon.list.reload` ausführen (CLI, REST oder
Dashboard). Die Action scannt den Ordner, installiert und aktiviert jede
noch nicht geladene Datei und lässt bereits geladene Addons unangetastet.
Fehlerhafte Pakete werden übersprungen und geloggt.

```text
helix> addon.list.reload
loaded 1 new addon:
helix.friends 1.0.0 [ENABLED]
```
