# Helix-Cloud

Helix-Cloud ist ein schlankes Orchestrierungssystem für Minecraft-Netzwerke,
konzeptionell angelehnt an CloudNet: eine Node verwaltet Tasks und startet
daraus Services (Paper-Server, Velocity-Proxies) — als Prozess oder im Docker.

Der einzige Startpunkt ist eine einzelne Jar:

```bash
./gradlew :helix-node:jar
java -jar helix-node/build/libs/Launcher.jar
```

Der Launcher legt beim ersten Start den `Helix/` Ordner mit aller
Konfiguration an.

Die Entwickler-Dokumentation liegt unter [`docs/`](docs/):

- [Architektur](docs/ARCHITECTURE.md) — Begriffe, Module, Datenflüsse
