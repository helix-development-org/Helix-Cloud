# IGuard: Debug-Modus, Trace-Recorder und Golden-Traces

Werkzeuge, um IGuard-Fehlalarme und Erkennungslücken nachvollziehbar zu machen.
Alle Befehle brauchen OP bzw. die genannten Permissions.

## Debug-Modus

```
/iguard debug <Spieler> [Check|all] [on|off]      (iguard.debug)
```

Zeigt jedem Betrachter (Spieler oder Konsole) jeden Verstoß des Spielers – auch weit
unter `alert-vl` – mit VL, Gewicht und Evidenz. Ohne `on|off` wird umgeschaltet; ein
einzelner Check lässt sich gezielt an-/abschalten. Pro Betrachter, Spieler und Check
höchstens eine Zeile je `debug.rate-limit-millis` (Standard 200 ms); zurückgehaltene
Zeilen zählen als `(+n)` in der nächsten. Farben: rot = ab Alert-Schwelle, gelb =
darunter, hell = im Signal-Cooldown („held“, erhöht den VL nicht).

## Live-Debug-Sidebar (GuardLab-Testserver)

Auf dem Task `IGuard-Test` ersetzt GuardLab die Netzwerk-Sidebar durch Live-Daten – für
jeden Betrachter seine eigenen, fünfmal pro Sekunde (`sidebar.interval-ticks: 4` in
`plugins/GuardLab/config.yml`, abschalten mit `sidebar.enabled: false`):

- laufende Suite: Fortschritt, Ergebnisse (✔ ⚠ ✖ ◇ Lücke, » übersprungen), aktuelles
  Szenario, Cheat an/aus, Lag-Profil, Phase mit Tick, Setbacks;
- Ping und TPS, IGuard-Frames und fehlgeschlagene Checks pro Sekunde;
- aktive Exemptions mit Scope und Umgebungs-Tags;
- die drei höchsten VLs mit Alarm-Schwelle (rot ab Alarm, gelb ab halber Schwelle);
- der letzte Flag mit Gewicht (oder „Cooldown“), Alter und den ersten Evidenz-Werten.

Die Tablist bleibt unverändert: Die Sidebar ist ein eigenes Objective auf dem Scoreboard
des Spielers, Rang-Prefixe und Teams darauf bleiben erhalten. Auf allen anderen Servern
ruht GuardLab.

## Trace-Recorder

```
/iguard trace start <Spieler> <Label>             (iguard.trace)
/iguard trace marker [Spieler] <Text>
/iguard trace stop [Spieler]
/iguard trace list
```

Schreibt `plugins/IGuard/traces/<Label>-<yyyyMMdd-HHmmss>.jsonl` – eine JSON-Zeile pro
Ereignis, asynchron gepuffert. Grenzen (statisch): `trace.max-bytes` (64 MiB),
`trace.max-seconds` (900 s), `trace.queue-capacity` (50 000 Frames; volle Queue →
Frame verworfen, im `end` gezählt). Ohne Spielername beziehen sich `stop`/`marker` auf
die zuletzt selbst gestartete bzw. die einzige laufende Trace.

Zeilentypen (`type`):

| Typ | Inhalt |
| --- | --- |
| `header` | `version`, `label`, `player`, `name`, `server`, `startedAt`, `clientVersion`, `recipe` |
| `env` | vollständiger Umgebungs-Snapshot (Position, Hitbox, Kollisionsboxen, Support, Slipperiness, Attribute, Effekte, Ping, TPS, Tags) – nur wenn er sich geändert hat |
| `target` | Hitbox-Frames des Angriffsziels (für Reach/Rotation) |
| `frame` | `t`, `seq`, `kind`, `cv`, `tick`, `packet` (Rohdaten), `env` (Zusammenfassung), `delta`, `eval` (predicted*, Uncertainty, airTicks … und `limits` wie `speedLimit`, `flyCeiling`, `climbCeiling`), `metrics` (Kampf/Welt/Inventar: Reach-Distanz, Look-Dot, CPS, Client-TPS …), `exempt`, `exemptions`, `results` (je Check `failed`, `counted`, `weight`, `vl`, `alertVl`, `evidence`), `vl` |
| `exempt` | externe Exemption (Sampler-Events, API) mit `millis`, `reason`, `scope` |
| `marker` | Freitext-Marker |
| `end` | `reason` (`stopped`, `quit`, `max-bytes`, `max-seconds`, `shutdown`), `frames`, `dropped` |

## IGuardApi (ServicesManager)

```kotlin
val api = server.servicesManager.load(IGuardApi::class.java)!!
val session = api.startTrace(player.uniqueId, "eis-sprint")
api.traceMarker(player.uniqueId, "jetzt springen")
api.stopTrace(player.uniqueId)
val handle = api.addTraceListener { report -> /* IGuardFrameReport je Frame, Worker-Thread */ }
api.exempt(player.uniqueId, Duration.ofSeconds(3), "launchpad", IGuardExemptionScope.MOVEMENT)
```

`exempt(…)` ohne Scope setzt alle Checks aus (auch Kampf/Welt); für reine
Bewegungseffekte `IGuardExemptionScope.MOVEMENT` verwenden. Listener laufen auf den
Check-Workern – schnell bleiben, keine Bukkit-Aufrufe.

## Golden-Traces (Regressionstests)

`helix-addon-guard-paper/src/test/resources/traces/<name>.jsonl` wird von
`GoldenTraceTest` durch eine frische `CheckEngine` (`TraceReplay`) geschickt und gegen
`<name>.expect.json` geprüft:

```json
{ "flags": ["movement.speed.a"], "clean": ["*"], "minCounted": { "movement.speed.a": 3 } }
```

`flags` müssen mindestens einmal anschlagen, `clean` nie (`*` = alle übrigen Checks),
`minCounted` verlangt eine Mindestzahl VL-erhöhender Verstöße. Eine Aufnahme vom
Testserver wird zum Regressionstest, indem man die `.jsonl` dort ablegt und die
Erwartung daneben schreibt. Die synthetischen Traces (`legit-*`, `cheat-*`) erzeugt
`IGUARD_REGENERATE_TRACES=1 ./gradlew :helix-addon-guard-paper:test --tests '*GoldenTraceTest*'`
(nur einzelne: `IGUARD_REGENERATE_TRACES=legit-mining,cheat-fastbreak-3x`).

FINISH_DIG-Frames tragen die Messung des Servers (`packet.dig`: `progress` = höchster
Abbaufortschritt pro Tick aus `Block.getBreakSpeed`, `samples`, `block`); damit prüft
`world.fastbreak.a` auch in Replays gegen die Vanilla-Abbauzeit.
