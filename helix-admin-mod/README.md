# Helix Admin – Fabric-Mod (Minecraft 1.21.11)

In-Game-Admin-Panel für Helix-Netzwerke: **Rechts-Shift** öffnet ein Schnellmenü im Stil von txAdmin,
„Open dashboard“ das volle Helix-Dashboard – beides als transparentes Overlay über dem laufenden Spiel
(eingebettetes Chromium über [MCEF Modern](https://modrinth.com/mod/mcef-modern)). Serverseitig gehört das
Addon `helix.admin` (Node + Paper-Teil) dazu; ohne Recht `helix.staff.menu` bleibt die Taste wirkungslos.

## Installation (Client)

In den `mods`-Ordner einer Fabric-Installation für 1.21.11:

- Fabric Loader ≥ 0.19.5, Fabric API
- Fabric Language Kotlin ≥ 1.14.0
- MCEF Modern 0.3.3+mc1.21.11 (lädt Chromium beim ersten Start einmalig herunter)
- `helix-admin-<version>.jar` (aus diesem Build bzw. `build/release/` von Helix-Cloud)

Die Taste lässt sich unter *Steuerung → Helix Admin* ändern. Esc oder ✕ schließt das Panel.

## Bauen

Eigener Gradle-Build mit eigenem Wrapper (Fabric Loom 1.18 braucht Gradle 9.7, der Rest von Helix nutzt 9.3):

```
cd helix-admin-mod && ./gradlew build   # → build/libs/helix-admin-<version>.jar
```

## Aufbau

| Datei | Aufgabe |
|---|---|
| `HelixAdminClient.kt` | Einstieg, Taste, Handshake (`hello`/`welcome`), Ticket anfordern |
| `AdminChannel.kt` | Plugin-Kanal `helix:admin` (JSON), Aufrufe mit Antwort (`call`/`result`) |
| `AdminScreen.kt` | Overlay mit Chromium-Browser (transparent, keine Pause), Eingaben |
| `JsBridge.kt` | `window.cefQuery` der Seite → lokale Befehle oder Ingame-Aktion auf dem Server |
