# helix-library-cinematic

Kamerafahrten für Paper-Plugins – Szenen aus Shots mit Pfaden, Blickzielen, Easing, Blenden und zeitgesteuerten
Events. Gebaut für den Cinematic-AFK-Modus, gedacht für alles Weitere: Intros, Map-Vorschauen, Siegerehrungen,
Tutorials, Kill-Cams.

## Einbinden

Das Plugin **HelixCinematic** (`helix-addon-cinematic-paper`, Teil des Addons `helix.cinematic`) installiert die
Library und bringt die Blenden-Schrift im Resource-Pack mit. Eigene Plugins:

```yaml
# plugin.yml
depend: [HelixCinematic]
```

```kotlin
// build.gradle.kts (im Monorepo)
compileOnly(project(":helix-library-cinematic"))
// außerhalb (z. B. MineClash): das Jar aus helix-library-cinematic/build/libs compileOnly einbinden
```

## Beispiel

```kotlin
val cinematics = Cinematics.shared()
val hero = Target.of(player, height = 1.6)

val intro = scene("arena-intro") {
    transition = Transition.FADE          // Standard zwischen den Shots (Transition.Cut = harter Schnitt)
    endTransition = Transition.FADE       // zurück in die eigene Sicht

    shot(100) {                           // 5 Sekunden
        path = CameraPath.spline(Vec3(0.0, 80.0, 0.0), Vec3(20.0, 75.0, 10.0), Vec3(30.0, 70.0, 30.0))
        look = Look.along()               // in Flugrichtung
        easing = Easing.SINE
        title(10, Component.text("Arena Rabenfels"), Component.text("Runde 1"))
    }
    shot(140) {
        path = CameraPath.orbit(hero, startAngle = 180.0, sweep = 50.0, radius = 10.0..4.0, height = 3.0..1.0)
        look = Look.at(hero)
        sound(0, Sound.sound(Key.key("entity.player.levelup"), Sound.Source.MASTER, 1f, 1f))
        at(120) { viewer -> viewer.sendMessage("Los geht's!") }
    }
}

cinematics.play(player, intro) {
    skipOnInput = false                   // Standard: jede Eingabe bricht ab
    spectate = true                       // Zuschauer = unsichtbare Kamera, keine Hotbar/Hand/Fadenkreuz
    standIn = true                        // Doppelgänger-NPC bleibt an seiner Stelle stehen (Skin, Rüstung, Name mit Rang)
    cleanScreen = true                    // Chat, Actionbar, Bossbars, Scoreboard ausblenden
    onFinish { reason -> if (reason == FinishReason.COMPLETED) startRound(player) }
}
```

Endlose Szenen (wie die AFK-Kamera) erzeugen ihre Shots on demand:

```kotlin
scene("showcase") {
    generate { index -> Shots.drone(player.world, hero, ticks = 160) }   // null beendet die Szene
}
```

## Bausteine

| Baustein | Varianten |
|---|---|
| `CameraPath` | `static`, `linear`, `spline` (Catmull-Rom durch Wegpunkte), `orbit` (um ein bewegtes Ziel, Radius/Höhe von→bis), `dolly` (auf ein Ziel zu) – oder eigene Lambda `CameraPath { t -> Vec3(…) }` |
| `Look` | `at(target)`, `along()`, `fixed(rotation)`, `pan(von, bis)` – oder eigene Lambda |
| `Target` | `at(location/vec)`, `of(entity, height)` – wird jedes Update neu gelesen, bewegte Ziele werden verfolgt |
| `Easing` | `LINEAR`, `SMOOTH` (Standard), `IN`, `OUT`, `IN_OUT`, `SINE` – oder eigene Lambda |
| `Transition` | `Cut`, `Fade(inTicks, holdTicks, outTicks)` (`FADE` = 8/4/10) |
| Events | `title(tick, …)`, `sound(tick, …)`, `at(tick) { viewer -> … }` |
| `Shots` | `flyIn(world, target)` – gerader Anflug mit leichter Drehung, mal schnell, mal langsam; `drone(world, target, ticks)` – Drohnen-Orbit; beide mit Kollisions- und Sichtprüfung; `clear(world, path, target)` |
| Stand-in | `PlaybackOptions.standIn` + `StandInSpawnEvent`/`StandInRemoveEvent` zum Dekorieren (Cosmetics machen das bereits), `StandIn.equip(slot, item)`, `StandIn.entityId` |

## Wie es funktioniert

- Kamera = unsichtbares Item-Display, nur für den Zuschauer sichtbar, per Vanilla-Kamera-Paket (packetevents) zur
  Kamera gemacht. Der Zuschauer bleibt im Spielmodus: sein eigener Körper (Name, Rang, Untertitel, Cosmetics) ist im
  Bild, Hotbar/Herzen/Hand blendet der Client aus.
- Bewegung als interpolierte Teleports alle 2 Ticks – der Client glättet dazwischen.
- Blenden: Vollbild-Glyphe `helix_cinematic:fade` als Titel (der Titel-Fade ist die Blende); der Schnitt passiert,
  während das Bild schwarz ist.
- Mit `spectate` wird der Zuschauer für die Dauer Spectator (für andere unsichtbar); Spielmodus und Position werden
  danach wiederhergestellt. `standIn` stellt einen INpc-Doppelgänger hin, `cleanScreen` filtert HUD-Pakete.
- Ein Spieler schaut höchstens eine Szene; `play` beendet eine laufende (`STOPPED`). Teleport, Weltwechsel und
  Verlassen beenden immer (`QUIT`), verlorene Ziele mit `TARGET_LOST`.
