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
  "description": "What it does."
}
```

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
  Fragen beantworten: Bridges (z.B. Maintenance-Bypass) und andere Addons
  fragen die Node über `POST /internal/permission-check`; eine Permission
  gilt als erteilt, sobald ein Resolver sie erteilt. Ohne registrierten
  Resolver wird jede Frage verneint.

```kotlin
context.registerPermissionResolver { request ->
    hasPermission(request.name, request.permission)
}
```

- `context.hasPermission(player, permission)` — fragt die aggregierten
  Resolver (z.B. das Permission-Addon) direkt.
- `context.onlinePlayers()` — alle Spieler im Netzwerk (die Proxies melden
  Join/Leave an die Node).
- `context.registerPlayerListener(listener)` — netzwerkweite
  Join/Leave-Events empfangen.
- `context.registerDisplayResolver { name -> ... }` — Prefix/Farbe eines
  Spielers für Chat und Tablist liefern (erster Nicht-null-Treffer gewinnt).
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
- `helix-addon-chat` — Chat-Format (`chat.format`) plus permission-basierte
  Prefix-Regeln (`chat.prefix.add/list/remove`), gerendert von der
  Paper-Bridge.
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

## Lifecycle

| Zustand | Bedeutung |
|---|---|
| `ENABLED` | geladen, Actions registriert |
| `DISABLED` | geladen, Actions entfernt, Classloader geschlossen |
| `FAILED` | Laden/Aktivieren fehlgeschlagen (Node läuft weiter) |

Verwaltung über CLI/REST/Dashboard: `addon.list`, `addon.enable <id>`,
`addon.disable <id>`.

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
