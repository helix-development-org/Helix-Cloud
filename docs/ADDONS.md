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

## Lifecycle

| Zustand | Bedeutung |
|---|---|
| `ENABLED` | geladen, Actions registriert |
| `DISABLED` | geladen, Actions entfernt, Classloader geschlossen |
| `FAILED` | Laden/Aktivieren fehlgeschlagen (Node läuft weiter) |

Verwaltung über CLI/REST/Dashboard: `addon.list`, `addon.enable <id>`,
`addon.disable <id>`.
