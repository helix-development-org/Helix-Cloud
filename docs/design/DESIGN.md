# Designkonzept des Netzwerks

> Version 1.0 · Stand 04.10.2026 · Richtung 2a „Kronland“. Quelle: Claude Design (`design-konzept/export/Designkonzept.html`). **Verbindlich** für alle Oberflächen. Abweichungen werden hier eingetragen, nicht im Einzelfall entschieden. „[Name]“ = Netzwerkname (offen); „Helix“ ist intern und für Spieler nie sichtbar.


## 1 · Leitidee

Ein verspieltes Mittelalter-Königreich im Cartoon-Stil. Materialien: dunkles Holz, Pergament, rotes Band mit Goldkante, Eisenbeschläge. Formensprache plastisch und freundlich: dicke dunkle Konturen, runde Ecken, Flächen mit Glanzkante oben und dunkler Unterkante. Villager mit Persönlichkeit sind das Gesicht des Servers. Ton: episch im Rahmen, humorvoll im Detail.

- **Spielzeug, nicht Museum**: Alles soll anfassbar wirken: dicke Kontur, Glanzkante, dunkle Unterkante. Lieber eine Form zu viel betont als zu flach.
- **Holz trägt, Pergament informiert**: Rahmen und Hintergründe sind Holz. Lesbare Inhalte stehen auf Pergament. Diese Trennung gilt in jedem Menü.
- **Rot ruft, Gold belohnt**: Band-Rot markiert Titel und Gefahr, Gold markiert Auswahl und Belohnung. Grün bestätigt.
- **Villager sprechen**: Wo ein Systemtext stehen könnte, darf ein Villager sprechen: Begrüßung, leere Zustände, Fehler, Tutorial.
- **Eine Kreatur, ein Merkmal**: Jeder Mob und Villager hat ein übertriebenes Erkennungsmerkmal, das auch als 16×16-Icon lesbar bleibt.
- **Ein System überall**: Ingame, Web und LabyMod nutzen dieselben Tokens und Namen. Keine Sonderfarben pro Addon.

## 2 · Lore-Rahmen

- **Das Reich**: [Name des Reichs]: ein Königreich aus Tälern, Dörfern und Arenen. Der Spieler kommt als Neuling aus der „Oberwelt“ an.
- **Das Zentrum**: [Zentrum – Arbeitstitel offen]: ein Ort oder Objekt in der Mitte der Lobby, um das sich die Welt ordnet. Visuell: Monument mit Gold und warmem Licht.
- **Die Villager**: Das Volk des Reichs. Sie verwalten, handeln, schmieden und richten Turniere aus. Sie halten sich für sehr wichtig.
- **Die Große Bindung**: Ein altes Ereignis, seit dem Mobs in Karten gebunden sind und nur für den kämpfen, der ihre Karte hält. Begründet MineClash.
- **Die Arenen**: Turnierplätze. Siege bringen Rosen, das Ehrenzeichen des Reichs.
- **Die Grenzlande**: Wildnis jenseits der Mauern (Kriegs-Server). Dort streifen ungebundene Mobs, die man als Karte einfangen kann.
- **Währungen**: Smaragde = Alltagsgeld der Villager · Diamanten = seltene Kostbarkeit · Rosen = Ehre, nur durch Kämpfe.
- **Neue Modi**: Erscheinen als neue Orte auf der Landkarte, vorher als Nebelfläche angedeutet.

Schreibregeln: Erzähler/Systemtexte sachlich-episch („Die Arena erwartet dich.“). Villager in Ich-Form mit Eigenheiten. Spieler werden geduzt. Keine Anglizismen in Lore-Texten außer feststehenden Spielbegriffen (Deck; Elixier heißt „Energie“).

## 3 · Farb-Tokens

Jede Farbe hat einen Token-Namen (Resourcepack-Palette, MiniMessage, CSS-Variable, LSS). Drei Stufen: hell (Glanzkante) · basis (Fläche) · dunkel (Unterkante/Schatten).

### Materialien

| Name | Token | hell | basis | dunkel | Verwendung |
|---|---|---|---|---|---|
| Holz | `wood` | `#7a4a24` | `#5a3418` | `#3b2617` | Rahmen, Menühintergrund, Dunkelmodus |
| Pergament | `parchment` | `#fffaf0` | `#f1e2c2` | `#d8c08e` | Inhaltsflächen, Infozettel, Hellmodus |
| Tinte | `ink` | `#4a2e1c` | `#2a160a` | `#1a0e06` | Kontur, Text auf Pergament, Schatten |
| Eisen | `iron` | `#d6dae0` | `#a8adb6` | `#6e737c` | Beschläge, Ecken, Scharniere |
| Tischdecke | `cloth` | `#c0302a` | `#8e2a1a` | `#5a160e` | Spielflächen (Kartentisch, Arenatafel) |

### Akzente

| Name | Token | hell | basis | dunkel | Verwendung |
|---|---|---|---|---|---|
| Band-Rot | `ribbon` | `#ff7a6a` | `#d8382e` | `#9a2018` | Titelband, Gefahr, Benachrichtigungs-Badge |
| Gold | `gold` | `#fff0a8` | `#ffcf4a` | `#e0a420` | Auswahl, aktiv, Belohnung, Rang-Prefix, Kronen-Währung |
| Smaragd | `emerald` | `#8cf09a` | `#4ac85e` | `#2a8a3e` | Bestätigen, Erfolg, Smaragd-Währung |
| Diamant | `diamond` | `#a8dcff` | `#5ab4ff` | `#2f7ad0` | Premium, Info, Diamant-Währung |
| Rose | `rose` | `#ffb0c0` | `#ff6a8a` | `#c8385a` | Rosen-Währung, Rang/Trophäen |

### Status

| Name | Token | hell | basis | dunkel | Verwendung |
|---|---|---|---|---|---|
| Info | `info` | `#a8dcff` | `#5ab4ff` | `#2f7ad0` | Hinweise, Tipps |
| Erfolg | `success` | `#8cf09a` | `#4ac85e` | `#2a8a3e` | Erfolgreiche Aktion |
| Warnung | `warn` | `#ffe08a` | `#ffb43a` | `#c87a10` | Achtung, Bestätigung nötig |
| Fehler | `error` | `#ff9a8a` | `#ff5a4a` | `#b8261a` | Fehler, Verbote |
| Gesperrt | `disabled` | `#c8bca8` | `#a89a84` | `#6e6252` | Inaktive Knöpfe, gesperrte Inhalte |

### Seltenheiten

| Name | Token | hell | basis | dunkel | Verwendung |
|---|---|---|---|---|---|
| Normal | `rarity.normal` | `#e2e6ec` | `#b8c0cc` | `#7a8494` | Karten, Truhen, Rahmen |
| Besonders | `rarity.special` | `#a8dcff` | `#5ab4ff` | `#2f7ad0` | „Besonders“ |
| Elite | `rarity.elite` | `#d2a8ff` | `#a05ae8` | `#6a2ab0` | ersetzt „Episch“ |
| Titan | `rarity.titan` | `#fff0a8` | `#ffcf4a` | `#e0a420` | Gold mit Ketten-Motiv |
| Boss | `rarity.boss` | `#ff9a8a` | `#e8403a` | `#9a2018` | Rot mit Glüh-Animation |

### Teams (MineClash)

| Name | Token | hell | basis | dunkel | Verwendung |
|---|---|---|---|---|---|
| Team Blau | `team.blue` | `#8ab8ff` | `#3a6ae0` | `#1c3a8a` | eigene Seite, Türme, Banner |
| Team Rot | `team.red` | `#ff8a7a` | `#d8382e` | `#7a1a12` | Gegnerseite, Türme, Banner |

Kontrast: Text auf Holz immer Pergament oder Gold (basis), Text auf Pergament immer Tinte. Weißer Text nur auf Band-Rot, Smaragd, Diamant und Rarity-Flächen, dann immer mit 1 px Tinten-Schatten. Grau (gesperrt) nie für Pflicht-Informationen.

## 4 · Form, Kontur, Licht

| Regel | Ingame (1 Texel = 1 GUI-px) | Web / LabyMod |
|---|---|---|
| Kontur | 1 px Tinte um jede Fläche, 2 px um Knöpfe und Rahmen | 3–4 px #2a160a, Tabellen 1 px |
| Ecken | Abgerundet durch weggelassene Eck-Pixel (1 px bei klein, 2 px Stufe bei Rahmen) | Radius 12–14 px (Knöpfe), 18–22 px (Panels), 999 px (Pills) |
| Glanzkante | 1 px Reihe „hell“ oben innen | inset 0 4px 0 hell |
| Unterkante | 2 px Reihe „dunkel“ unten innen | inset 0 -6px 0 dunkel |
| Schlagschatten | 1 px Tinte unter Panels (versatz y+1); Text mit Minecraft-Schatten | 0 4–8px 0 Tinte, kein Weichzeichner |
| Licht | Immer von oben links; Glanzpixel oben links an runden Objekten | identisch |
| Schattierung | Max. 3 Stufen pro Farbe + Kontur, kein Dithering auf Knöpfen; Rauschen nur auf Holz/Pergament, sparsam | flache Flächen, keine Verläufe außer Glow bei Boss |
| Gedrückt | Glanzkante weg, Inhalt 1 px nach unten, Unterkante 1 px | translateY(2px), Unterkante halbiert |
| Abstände | Raster 2 px; Innenabstand Panel 6 px, zwischen Gruppen 4 px | Raster 4 px; 8 / 12 / 16 / 24 / 32 |

## 5 · Typografie

- **Ingame**: Ausschließlich Minecraft-Standardschrift (8 px Zeilenhöhe). Keine eigene Schrift im Resourcepack, bis Version 2 des Konzepts.
- **Ingame Titel**: Standardschrift, Gold (#ffcf4a) mit Tinte-Schatten auf Band-Rot; Wörter in Großbuchstaben am Wortanfang, kein KOMPLETT-GROSS.
- **Ingame Text**: Tinte (#2a160a) ohne Schatten auf Pergament; Pergament (#f1e2c2) mit Schatten auf Holz.
- **Web Display**: Lilita One (Google Fonts), für Titel, Knöpfe, Badges. Titel mit 2 px Tinten-Stroke und 4 px Schlagschatten.
- **Web Text**: Nunito 500/700/800, Fließtext 15–16 px, Zeilenhöhe 1.5–1.65.
- **Web Code**: JetBrains Mono 12–13 px für Befehle, Logs, Konsole, IDs.
- **Größen Web**: 56 / 34 / 22 / 18 / 16 / 14 / 12 px. Unter 12 px nichts.
- **Zahlen**: Tausender mit Punkt (1.240), Zeiten als 1 h 20 min, Countdown als 08:12.

## 6 · Ingame-Menüs: Raster und Aufbau

Fläche 384×256 px bei x = −104, y = −17 relativ zum Kistenfenster (176×222). Klickbar nur das 9×10-Raster (18×18-Zellen).

- **Fläche**: 384×256 px, als Font-Glyphen gekachelt (max. 256 px pro Glyph).
- **Außenrahmen**: 8 px Holzrahmen mit Eisenecken (12×12) an allen vier Ecken; 1 px Tinte außen.
- **Titelband**: Mittig oben, ragt 6 px über den Rahmen; Höhe 16 px, Textzeile mittig.
- **Inhaltszonen**: Links Liste/Sammlung, Mitte Aktion, rechts Infozettel. Zonen immer an Zell-Spalten ausrichten.
- **Zellen**: Klickbare Elemente sind 18×18 oder Vielfache; Grafik eines Knopfs deckt die Zellen exakt ab, damit Hover-Rahmen und Grafik übereinstimmen.
- **Hover**: Minecraft-Hover-Rahmen bleibt. Zusätzlich darf ein Item-Wechsel einen Gold-Rahmen zeigen.
- **Tooltip**: Erste Zeile Name in Token-Farbe, zweite Zeile Kategorie in Grau, danach Werte, letzte Zeile Aktion in Gold („Klick: Wählen“).
- **Navigation**: Zurück immer unten links (Pfeil links), Schließen nie als eigener Knopf (ESC). Blättern unten mittig: Pfeil · Seitenzahl · Pfeil.

### Menü-Typen

- **Pergament-Buch** (Briefe, Profil, Übersetzungen, Anticheat, Freunde, Clans): Holzpult mit aufgeschlagenem Buch, linke Seite Liste, rechte Seite Detail. Basis: ledger/background.png, neu mit Tinten-Kontur.
- **Tisch** (Kartentisch, Shop, Schmied, Forscher): Draufsicht auf Tisch mit Tischdecke. Gegenstände liegen darauf; Infozettel rechts.
- **Landkarte** (Navigator): Pergamentkarte auf Holz, Orte als animierte Embleme (Abschnitt 14).
- **Tafel** (Truhen, Clan-Krieg, Ranglisten): Holztafel mit Bannern links/rechts in Teamfarben, Pergament-Aushang in der Mitte.

## 7 · Komponenten

| Komponente | Beschreibung | Spezifikation |
|---|---|---|
| Rahmen | Holz 8 px mit Eisenecken. Innenkante 1 px Gold bei wichtigen Menüs (Shop, Truhen). | wood + iron · Kontur ink · innen gold.base 1px |
| Titelband | Rotes Band mit eingerollten Enden, Goldkante oben und unten. Text Gold mit Schatten. | ribbon.base · Kante gold.base 1px · Höhe 16px |
| Knopf primär | Smaragd-Grün für Bestätigen/Wählen/Kaufen. | emerald · min. 36×18 px (2 Zellen) · Text weiß + Schatten |
| Knopf aktiv | Gold für ausgewählte Optionen und Tabs. | gold · Text ink ohne Schatten |
| Knopf sekundär | Pergament für Abbrechen, Zurück, neutrale Aktionen. | parchment · Text ink |
| Knopf Gefahr | Band-Rot für Entfernen, Bannen, Auflösen. Immer mit Rückfrage. | ribbon · Text weiß + Schatten |
| Knopf gesperrt | Grau, Kontur grau statt Tinte, kein Glanz. Tooltip erklärt warum. | disabled · Kontur disabled.dark |
| Pfeile | Holzpfeile mit Goldspitze, 18×18. Gesperrt: grau. | ledger/arrow_left/right überarbeiten |
| Tabs | Lesezeichen am Buchrand. Aktiv: Gold und 2 px länger, inaktiv: Holz hell. | 18×24 px · max. 6 Tabs |
| Listenzeile | Pergamentstreifen, Kopf/Icon links (16×16), Name, rechts Status-Badge. Ausgewählt: Gold-Fläche. | Höhe 18 px · Trennlinie parchment.dark gestrichelt |
| Badge | Pill mit Kontur. Rot für Anzahl Neu, Grün für Online, Grau für Offline, Gold für Rang. | Höhe 9 px ingame · Text 1 Zeile |
| Infozettel | Pergament mit Reißzwecke oder Siegel oben. Für Details einer Auswahl. | parchment · Siegel ribbon.base |
| Kachel | Quadratisch, Rarity-Farbe als Fläche, Inhalt-Icon mittig, Titel darunter. | 36×36 oder 54×54 px |
| Leerer Zustand | Immer ein Villager-Kopf + ein Satz in seiner Stimme, nie nur „Keine Einträge“. | z. B. Briefe leer: Knöpfchen „Noch keine Post. Niemand mag dich. Spaß!“ |
| Fortschritt | Holzrinne, Füllung in Token-Farbe mit Glanzkante; Zahl rechts daneben. | Höhe 6 px · Truhen-Countdown: diamond |
| Eingabe | Amboss-/Schild-Eingabe bleibt Minecraft-nativ; davor ein Hinweis im Pergament-Stil. | — |

## 8 · Icons und Item-Texturen

- **Größen**: 16×16 für Items und Inline-Icons, 32×32 für Menü-Embleme und Kacheln, 64×64 nur für Karten.
- **Kontur**: 1 px Tinte rundum (auch bei 32×32), Ecken der Kontur weglassen für weichere Form.
- **Licht**: Oben links. Ein Glanzpixel (oder 2×2 bei 32 px) in „hell“.
- **Farben**: Max. 3 Stufen pro Farbe + Tinte. Nur Palette-Farben aus Abschnitt 3.
- **Silhouette**: Muss als einfarbige Fläche erkennbar sein; Test: Icon in Tinte füllen und prüfen.
- **Hintergrund**: Transparent. Kein Rahmen im Icon selbst; Rahmen kommt von der Komponente.
- **Karten 64×64**: 4 px Rarity-Rahmen mit Tinten-Kontur, Bildfeld mit Mob-Kopf in Frontalansicht, Kosten-Tropfen unten links (Diamant-Blau mit Zahl), Name nicht auf der Karte (steht im Tooltip).
- **Truhen**: Je Seltenheit eigene Farbe der Beschläge, Form gleich. Kriegstruhen mit Banner in Teamfarbe.
- **Animiert**: Frame-Streifen 16 px breit, 8–12 Frames, 2–4 Ticks pro Frame. Nur für Embleme, Kerzen, Fackeln, Edelsteine.

- **Krone (Netzwerkwährung)**: 16×16, Zackenkrone in Gold (hell/basis/dunkel) mit drei Edelstein-Pixeln (Band-Rot, Smaragd, Diamant), Tinten-Kontur; Glyph im Netzwerk-Pack für Actionbar, Chat und Preis-Pills.

Ist-Stand Währungs-Icons: Form bleibt, Kontur auf Tinte umstellen, Glanzpixel oben links ergänzen.

## 9 · Chat und Text

| Art | Regel | MiniMessage |
|---|---|---|
| Prefix | [Name] in Gold, danach » in Holz-hell. Kurzform für Actionbar/Scoreboard: Initiale in Gold. | `<#ffcf4a>[Name]</#ffcf4a> <#7a4a24>»</#7a4a24> ` |
| Standardtext | Pergament. Hervorhebungen (Namen, Zahlen) in Gold. | `<#f1e2c2>Du hast <#ffcf4a>30 Rosen</#ffcf4a> erhalten.` |
| Info | Diamant-Blau, beginnt mit „Hinweis:“ nur wenn nötig. | `<#5ab4ff>…` |
| Erfolg | Smaragd-Grün, kurz, Vergangenheitsform. | `<#4ac85e>Karte gekauft: <#ffcf4a>Hexe` |
| Warnung | Orange, beginnt mit „Achtung:“. | `<#ffb43a>Achtung: …` |
| Fehler | Rot, sagt was zu tun ist, nicht nur was falsch ist. | `<#ff5a4a>Das Deck ist voll. Entferne erst eine Karte.` |
| Villager-Rede | Name in Robenfarbe des Villagers mit Doppelpunkt, Text Pergament, kein Prefix. Max. 2 Zeilen, dann Pause. | `<#4ac85e>Mira Feilsch:</#4ac85e> <#f1e2c2>…` |
| Klickbar | Gold, unterstrichen nur beim Hover; Hover-Text erklärt die Aktion. | `<click:run_command:/mc deck><hover:show_text:"Kartentisch öffnen"><#ffcf4a>[Deck öffnen]` |
| Staff-Chat | Prefix [Team] in Rot, danach Rang-Tag des Absenders (Owner, Manager, Developer, Admin, Mod, Support), Inhalt Pergament. | `<#d8382e>[Team]</#d8382e> …` |
| Spieler-Chat | {prefix}{color}{name}{suffix} » {message}; Pfeil in Holz-hell, Nachricht Pergament. | `{prefix}<color>{name}</color> <#7a4a24>»</#7a4a24> <#f1e2c2>{message}` |

### Team-Ränge

| Tag | Farbe | Gewicht |
|---|---|---|
| [Owner] | `#d8382e` | 100 |
| [Manager] | `#ff6a8a` | 95 |
| [Developer] | `#a05ae8` | 90 |
| [Admin] | `#ff5a4a` | 80 |
| [Mod] | `#ffb43a` | 70 |
| [Support] | `#4ac85e` | 60 |
| [Spieler] | `#c8bca8` | 0 |

## 10 · HUD, Tablist, Scoreboard, MOTD

| Element | Regel | Beispiel |
|---|---|---|
| Actionbar | Währungen dauerhaft: Icon + Zahl, getrennt durch zwei Leerzeichen. Bei Spielmodus-Info (Krieg) wechselt sie für 3 s und kehrt zurück. | `<emerald> 1.240  <diamond> 35  <rose> 620` |
| Bossbar | Farbe nach Inhalt: Tutorial gelb, Turm-Stand nach Team, Countdown blau. Text: Titel in Gold, Wert in Pergament. | `Schritt 2/6 · Deck ansehen` |
| Tablist | Kopf: Wortmarke als Glyph + Zeile „[Name] · {online} Spieler“. Fuß: Server + Discord in Holz-hell. Max. 2 Animationsframes pro Sekunde. | `{network} · {online}/{max}` |
| Scoreboard | Titel = Wortmarke-Glyph. Zeilen: Label in Holz-hell, Wert in Pergament, Trenner leer. Max. 9 Zeilen in der Lobby. | `Rosen » 620` |
| Nametag | Zeile 1: Rang-Prefix + Name in Rangfarbe, Clan-Tag in Grau dahinter. Zeile 2 (Untertitel): kursiv, Pergament-dunkel. | `[Mod] Steve [ABC]` |
| MOTD | Zeile 1: Wortmarke in Gold + Kurzslogan. Zeile 2: Aktuelles (Event, Update) in Pergament mit Rot als Akzent. Wartung: Zeile 2 in Orange. | `<#ffcf4a>[Name] <#7a4a24>· <#f1e2c2>Das Kartenturnier beginnt` |
| Titel/Untertitel | Nur für Meilensteine (Sieg, Truhe Boss, Level-Up). Titel Gold, Untertitel Pergament. Einblenden 10, Stehen 40, Ausblenden 10 Ticks. | `SIEG! · +30 Rosen` |

## 11 · MineClash

MineClash ist Teil der Welt, kein eigenes Branding; dieselben Tokens und Komponenten.

### Seltenheiten (verbindlich, „Episch“ entfällt)

- **Normal** (`#b8c0cc` / `#7a8494`, Text `#2a160a`): Locker gebunden. Grauer Rahmen.
- **Besonders** (`#5ab4ff` / `#2f7ad0`, Text `#2a160a`): Blauer Faden. Rahmen mit 1 Glanzpunkt.
- **Elite** (`#a05ae8` / `#6a2ab0`, Text `#ffffff`): Runensiegel im Rahmen.
- **Titan** (`#ffcf4a` / `#e0a420`, Text `#2a160a`): Goldene Ketten an den Ecken.
- **Boss** (`#e8403a` / `#9a2018`, Text `#ffffff`): Glühender Rahmen, 4-Frame-Animation.

- **Kartentisch**: Typ „Tisch“. Holztisch mit roter Tischdecke (cloth), Sammlung links im Holzfach, Deck 4×2 mittig auf der Decke, Infozettel rechts. Kerze rechts unten bleibt als Lebenszeichen.
- **Kampf-Panel**: Ersetzt die Hotbar: Holzleiste mit 4 großen Kartenplätzen + kleiner nächster Karte. Ausgewählt: 2 px Gold-Rahmen und 2 px nach oben. Zu teuer: 50 % Tinte darüber, Kosten in Rot.
- **Energie**: XP-Leiste in Diamant-Blau (Resourcepack), Zahl darüber. 2× Energie: Leiste pulsiert, 3×: goldener Rand.
- **Türme**: Steinsockel + Dach in Teamfarbe, Krone auf dem Haupt-Turm. HP-Leiste über dem Turm in Teamfarbe mit Tinten-Rahmen.
- **Truppen-HP**: Kurze Leiste über dem Mob, Teamfarbe; unter 30 % HP wechselt sie zu Fehler-Rot.
- **Truhen**: Gleiche Form, Beschläge in Rarity-Farbe. Bereit: goldenes Glimmen (Partikel) + Sound. Öffnen: Deckel springt, Karten fliegen in einem Bogen zum Spieler.
- **Schatzhalle**: Podeste aus Holz mit Eisen, Statusschild als Pergamentzettel mit Countdown in Diamant-Blau.
- **Shop**: Typ „Tisch“ mit Händlerin-Kopf oben links. Tagesangebote als 3 Kacheln, Preis-Pill darunter. Ausverkauft: Stempel „Weg!“ in Rot quer.

## 11b · CityBuild „Kronstadt“

CityBuild ist die Handelsstadt des Reichs (Konzept: `CityBuild/CityBuild-Spielkonzept.md`). Kein eigenes Branding; dieselben Tokens und Komponenten. In MineClash wird CityBuild nie erwähnt, der Einstieg ist nur der Navigator.

- **Währung**: Kronen (Token `gold`, Icon §8), netzwerkweit. Actionbar in CityBuild: `<krone> 12.340`.
- **Krämer**: Typ „Tisch“ mit Krämer-Kopf oben links, Kategorien als Reiter, Waren als Kacheln mit Preis-Pill (Kauf Smaragd, Ankauf Gold). Sonderangebote mit Gold-Rahmen und Vorrat-Pill; ausverkauft: Stempel „Weg!“.
- **Grundbuch, Auktionshaus, Plot-Markt, Homes, Warps, Instanzwahl**: Pergament-Buch; Instanzen mit Status-Pill (grün offen, gold fast voll, rot voll, grau Wartung).
- **Shop-Schild**: Zeile 1 `[Shop]` in Gold, Art in Smaragd (Kauf) oder Diamant (Verkauf), Menge und Preis in Tinte.
- **Schmiede (Team)**: Vollbild-Werkbank, Vorschau-Slot mittig auf einem Amboss, Reiter als Holzschilder. Admin-Items tragen optional die Lore-Zeile „Kronland-Schmiede“ in Gold-dunkel.
- **Schatzkisten**: Beschläge in Rarity-Farbe (§11), Gewinne rotieren als ItemDisplay über der Kiste, Treffer mit Partikeln und Sound aus `reich`.
- **Farmwelt-Reset**: Countdown in der Bossbar (blau, §10), Warntext Orange.

## 12 · Villager

Villager sind Charaktere: Name, Beruf, Haltung, Requisit, Sprechweise, Mimik-Set. Referenz: `villager-vorlage.js` (3D-Entwurf mit benannten Teilen).

### Model-Sheet

- **Maßeinheit**: 1 Einheit = 1 Pixel = 1/16 Block. Texturen 1:1 (kein Mixed-Scale innerhalb eines Modells).
- **Gesamthöhe**: 32 px (2 Blöcke), wie Vanilla. Kinder/Lehrlinge 22 px.
- **Kopf**: 12×12×11 px (Vanilla 8×10×8). Etwa 37 % der Höhe. Kopf-Pivot am Hals.
- **Körper**: Gerade Robe 9×12×6, endet an der Hüfte. Kragen 7×1.4×6.4, Knopfleiste 1.6 px breit, Gürtel 1.2 px mit Schnalle.
- **Beine**: 2× 3.6×7×3.6 in Hose, Schuhe 4×2×5. Keine Rock-Verbreiterung.
- **Arme**: Verschränkt wie Vanilla, aber in Teilen: 2 Ärmel (3×3.4×5) + Unterarm-Balken (11.2×3.2×3.4) + Manschette + Hände (3.6×2.8×3.6).
- **Nase**: 3.4×6.2×3.4, eigenes Pivot an der Nasenwurzel. Wichtigstes Erkennungsmerkmal, darf beim Sprechen pendeln.
- **Augen**: Weiß 3.2×2.4, Iris 1.7×2.1 (Grün Standard), Pupille 0.8×1.1. Iris nach innen versetzt (leichtes Schielen = freundlich).
- **Lider**: Eigene Teile mit Pivot an der Oberkante, skaliert von 0.05 (offen) bis 1 (zu).
- **Braue**: Durchgehende Braue 9.4×1.7, eigenes Pivot. Charakter über Dicke und Winkel.
- **Mund**: Dunkle Mundplatte 6.4×1.6 unter der Nase, skaliert in y zum Sprechen.
- **Textur**: Kräftige Flächen, 1 px dunklere Kante an allen Außenkanten, max. 3 Stufen pro Farbe, kaum Rauschen. Robe in Berufsfarbe, Details in Gold/Leder.
- **Benennung**: Teile englisch und eindeutig: head_pivot, brow_pivot, lid_pivot_l, nose_pivot, mouth_pivot, robe, sleeve_l … (für Animationen und Code).
- **Requisit**: Jeder Beruf hat ein großes Requisit, das die Silhouette verändert (Hut, Hammer, Helm, Kopftuch).

### Animationen

| Animation | Beschreibung | Timing |
|---|---|---|
| Idle-Wippen | Körper hebt sich leicht, nie ganz still. | 2 s · ±0.35 px |
| Blinzeln | Lider zu und wieder auf. | alle 2.5–6 s · 3 Ticks |
| Kopf folgt | Kopf dreht zum nächsten Spieler im Radius 6, sonst langsames Umschauen. | max. ±35° |
| Braue hebt | Bei Ansprechen, Überraschung, Angebot. | 0.9 s · +0.9 px |
| Sprechen | Mund skaliert, Nase pendelt synchron. | 9 Hz · solange Text läuft |
| Begrüßen | Arme lösen sich, eine Hand hebt sich kurz. | 1 s · beim Nähern |
| Berufs-Geste | Hämmern, Münzen zählen, Karten mischen, Schlüssel klimpern. | alle 8–15 s |
| Jubel | Arme hoch, kleiner Hüpfer. | bei Sieg, Kauf, Upgrade |
| Schmollen | Arme fester, Kopf zur Seite, Braue tief. | bei Niederlage, Fehler |
| Schlafen | Kopf sinkt, Lider zu, Z-Partikel. | nachts im Dorf |

### Charaktere

#### Bertold Knubbel – Bürgermeister

- Ort: Lobby, Dorfplatz
- Robe: Weinrot, Goldsaum
- Requisit: Zylinderhut mit Goldband, Medaillon an Kette
- Wesen: Eitel, herzlich, übertreibt alles.
- Spricht: Feierlich, lange Sätze, Superlative.
- Mimik: Braue oft oben, breites Lächeln
- Zitat: „Ich habe diesen Brunnen persönlich eröffnet. Zweimal.“

#### Mira Feilsch – Händlerin

- Ort: Shop
- Robe: Smaragdgrün
- Requisit: Lila Kopftuch, Münzbeutel am Gürtel
- Wesen: Freundlich, geschäftstüchtig.
- Spricht: Schnell, Zahlen, Augenzwinkern.
- Mimik: Ein Auge zugekniffen beim Angebot
- Zitat: „Für dich? Sonderpreis. Also der normale.“

#### Gorm Faust – Kampfmeister

- Ort: Arena-Eingang, Match-Queue
- Robe: Eisengrau mit roter Schärpe
- Requisit: Holzschwert, Narbe über der Braue
- Wesen: Laut, ehrlich, feuert jeden an.
- Spricht: Kurze Ausrufe, viele Ausrufezeichen.
- Mimik: Grinsen, Braue tief und dick
- Zitat: „Ab in die Arena! Ich halte deine Rosen.“

#### Elsa Blatt – Kartenmeisterin

- Ort: Kartentisch-Pavillon
- Robe: Tintenblau
- Requisit: Runde Brille, Kartenfächer
- Wesen: Ruhig, präzise, belehrend.
- Spricht: Sachlich, Tipps zur Taktik.
- Mimik: Brille rutscht, Braue einseitig
- Zitat: „Ein Zombie allein ist traurig. Drei sind eine Strategie.“

#### Klunk – Truhenwächter

- Ort: Schatzhalle
- Robe: Braun mit Eisenbeschlägen
- Requisit: Riesiger Schlüsselbund
- Wesen: Wortkarg, liebt seine Truhen.
- Spricht: Ein bis drei Wörter.
- Mimik: Halb geschlossene Lider
- Zitat: „Noch acht Stunden. Sie schläft.“

#### Pitt Stollen – Minenarbeiter

- Ort: Mine
- Robe: Erdbraun
- Requisit: Gelber Helm mit Lampe, Rußfleck
- Wesen: Begeistert von jedem Stein.
- Spricht: Fröhlich, Bergmanns-Sprüche.
- Mimik: Weit offene Augen
- Zitat: „Heute glitzert’s! Oder ich hab Staub im Auge.“

#### Hedda Wall – Clanwärterin

- Ort: Clanhalle
- Robe: Dunkelrot
- Requisit: Clan-Register mit Siegel
- Wesen: Streng, fair, Regeln über alles.
- Spricht: Amtlich, Paragraphen-Ton.
- Mimik: Braue gerade, Mund schmal
- Zitat: „Ohne Siegel kein Clan. Ohne Clan kein Krieg.“

#### Prof. Funkel – Forscher

- Ort: Forschungsturm (Kartenlevel)
- Robe: Lila mit Brandflecken
- Requisit: Lupe, zerzauste Haarbüschel
- Wesen: Zerstreut, euphorisch.
- Spricht: Unterbricht sich selbst.
- Mimik: Augen unterschiedlich weit offen
- Zitat: „Faszinierend! Und leicht entflammbar.“

#### Ambo Hammerl – Schmied

- Ort: Schmiede (Perks)
- Robe: Grau, Lederschürze
- Requisit: Großer Hammer, dicke Braue
- Wesen: Kräftig, gutmütig.
- Spricht: Kurz, mit Lautmalerei („Klong.“).
- Mimik: Dicke Braue, zufriedenes Nicken
- Zitat: „Schnellere Schlösser? Klong. Erledigt.“

#### Knöpfchen – Lobby-Erzähler

- Ort: Lobby, Tutorial, leere Zustände
- Robe: Senfgelb, zu groß
- Requisit: Notizbuch und Feder
- Wesen: Kleiner Lehrling, neugierig, irrt oft.
- Spricht: Fragend, erklärt mit falscher Sicherheit.
- Mimik: Große Augen, Braue hoch
- Zitat: „Das Reich ist alt. Mindestens hundert. Glaube ich.“

### CityBuild-Figuren (Kronstadt)

Nur auf den CityBuild-Servern. Model-Sheet nach `villager-vorlage.js`, bis zum Blockbench-Modell Standard-Villager mit Profession.

#### Lotte Laterne – Stadtführerin

- Ort: Kronstadt-Spawn, Tutorial
- Robe: Himmelblau
- Requisit: Laterne an langem Stab
- Wesen: Fröhlich, zeigt auf alles.
- Spricht: Ausrufe, Wegbeschreibungen.
- Mimik: Strahlend, Arm ausgestreckt
- Zitat: „Da drüben! Nein, das andere Drüben.“

#### Gundula Grundmann – Katasterbeamtin

- Ort: Rathaus (Plots, Grundbuch, Plot-Markt)
- Robe: Schiefergrau
- Requisit: Maßband, dicker Foliant, Stempel
- Wesen: Pedantisch, liebt Ordnung.
- Spricht: Amtsdeutsch, nummeriert alles.
- Mimik: Brille auf der Nasenspitze
- Zitat: „Grundstück 4-17. Gestempelt. Zweifach.“

#### Fritz Feilbiet – Krämer

- Ort: Markt (Admin-Shop, Sonderangebote)
- Robe: Kürbisorange mit Schürze
- Requisit: Waage, Bleistift hinterm Ohr
- Wesen: Laut, preist alles an.
- Spricht: Marktschreier, Ausrufezeichen.
- Mimik: Mund weit offen
- Zitat: „Frische Steine! Heute noch steiniger!“

#### Ludwig Lot – Auktionator

- Ort: Auktionshalle
- Robe: Dunkelgrün mit Weste
- Requisit: Auktionshammer
- Wesen: Schnell, feierlich.
- Spricht: „Zum Ersten …“, zählt laut.
- Mimik: Hammer erhoben
- Zitat: „Zum Zweiten … und verkauft an den Herrn mit dem Hut!“

#### Henni Hacke – Vorarbeiterin

- Ort: Jobbrett am Portalplatz
- Robe: Erdrot
- Requisit: Spitzhacke über der Schulter
- Wesen: Anpackend, kurz angebunden.
- Spricht: Knappe Befehle.
- Mimik: Ärmel hochgekrempelt
- Zitat: „Reden später. Erst Schicht.“

#### Waldemar Moos – Förster

- Ort: Portalplatz (Farmwelt)
- Robe: Moosgrün
- Requisit: Axt, Vogelnest auf dem Hut
- Wesen: Ruhig, naturverliebt.
- Spricht: Langsam, flüstert.
- Mimik: Halb lächelnd, Augen zu
- Zitat: „Sonntag wächst alles neu. Der Wald verzeiht.“

#### Rupert Ranzen – Quartiermeister

- Ort: Rathaus-Keller (Kits)
- Robe: Ockerbraun
- Requisit: Riesiger Rucksack, Klemmbrett
- Wesen: Ordentlich, zählt nach.
- Spricht: Listen, Zahlen.
- Mimik: Zusammengekniffene Augen
- Zitat: „Drei Fackeln fehlen. Ich zähle nochmal.“

#### Paula Paket – Postbotin

- Ort: Markt (Tägliche Belohnung)
- Robe: Postgelb mit roten Säumen
- Requisit: Umhängetasche voller Briefe
- Wesen: Eilig, außer Atem.
- Spricht: Hastig, halbe Sätze.
- Mimik: Schweißtropfen, Wangen rot
- Zitat: „Für dich! Unterschreib – nein, keine Zeit!“

#### Brunhilde Brecher – Waffenmeisterin

- Ort: Arenator
- Robe: Eisengrau mit Rot
- Requisit: Rundschild, Holzschwert
- Wesen: Rau, herzlich.
- Spricht: Laut, klopft auf Schultern.
- Mimik: Grinsen, Narbe an der Braue
- Zitat: „Hinfallen ist erlaubt. Liegenbleiben nicht.“

#### Greta Goldzahn – Schatzmeisterin

- Ort: Schatzkammer am Markt (Schatzkisten)
- Robe: Purpurbraun mit Goldknöpfen
- Requisit: Goldzahn, Schlüsselring
- Wesen: Verschmitzt, geheimnisvoll.
- Spricht: Flüstert Gewinne.
- Mimik: Ein Auge zugekniffen, Goldzahn blitzt
- Zitat: „Psst. In der dritten Kiste … nein, ich sag nichts.“

#### Kuno Kurbel – Stellmacher

- Ort: Markt (Fahrzeuge, `/stellmacher`)
- Robe: Rostbraun mit Lederschürze
- Requisit: Schraubenschlüssel, Wagenrad auf dem Rücken
- Wesen: Tüftler, stolz auf jede Speiche.
- Spricht: Technisch, schwärmt von Achsen und Federung.
- Mimik: Ölfleck auf der Wange, eine Braue hoch
- Zitat: „Vier Räder, ein Traum. Drei gehen auch.“

#### Tilda Tapete – Möbelhändlerin

- Ort: Markt (Möbel, Hüte & Kostüme, `/moebel`)
- Robe: Altrosa mit Spitzenkragen
- Requisit: Stoffmuster-Fächer, Zollstock
- Wesen: Stilbewusst, dramatisch.
- Spricht: Geschmacksurteile, seufzt gern.
- Mimik: Lider halb gesenkt, Kinn hoch
- Zitat: „Dieser Stuhl? Der sagt so viel über dich.“

#### Frieda Feder – Tierhändlerin

- Ort: Markt (Haustiere, Nutztier-Eier, Futter, `/tierhandel`)
- Robe: Wiesengrün mit Strohhut
- Requisit: Futterbeutel, Huhn unterm Arm
- Wesen: Bodenständig, warmherzig.
- Spricht: Dialekt-Anklang, redet mit den Tieren.
- Mimik: Breites Lachen, Sommersprossen
- Zitat: „Gackert's, hat's Hunger. Gackert's nicht, auch.“

## 13 · Mobs und Kartenkreaturen

- **Proportion**: Kopf um 30–50 % vergrößert gegenüber Vanilla, Körper leicht gestaucht, Gesamtgröße wie Vanilla.
- **Gesicht**: Augen, Mund und ein Merkmal als eigene Teile, damit sie animiert reagieren (Angriff, Treffer, Tod).
- **Merkmal**: Pro Mob ein übertriebenes Detail (Liste unten). Auf der Karte ist genau dieses Merkmal im Bild.
- **Teamfarbe**: Ein kleines Stoffteil (Halstuch, Band, Fahne) in Teamfarbe, damit Freund und Feind in der Arena lesbar sind.
- **Ausdruck**: Normal: grimmig-komisch, nie gruselig-realistisch. Boss: bedrohlich, aber mit Cartoon-Augen.
- **Reaktionen**: Spawn: Plopp + Staubwolke. Treffer: weißes Aufblitzen 2 Ticks + Augen zu. Tod: Umfallen + Karte, die aufsteigt.
- **Textur**: Wie Villager: Flächen, Kante 1 px dunkler, Palette aus Abschnitt 3 plus mob-eigene Grundfarbe.

- **Zombie**: Hängende Arme, ein Auge größer, zerrissenes Hemd.
- **Skelettbogenschütze**: Großer Bogen, Kiefer klappert, Helm etwas zu groß.
- **Höhlenspinne**: Viele Augen in verschiedenen Größen, Giftzahn-Tropfen.
- **Snow Golem**: Kürbis mit breitem Grinsen, Zweigarme winken.
- **Evoker**: Spitze Kapuze, Hände mit Runenglühen, sehr hohe Braue.
- **Phantom**: Breite Flügel, große leuchtende Augen, Maul offen.
- **Hexe**: Riesige Warzennase, Hut mit Knick, Trank am Gürtel.
- **Blaze**: Kleiner Kopf, Stäbe als Flammenkrone, wütende Augenbrauen aus Feuer.
- **Slime**: Wackelnde Würfelform, Augen schwimmen im Körper; Kinder kleiner und frecher.
- **Enderman**: Lange Glieder, Augen als lila Schlitze, teleportiert mit Glitch-Pose.
- **Iron Golem**: Riesige Fäuste, Blume auf der Schulter, Nase wie Villager.
- **Enderdrache**: Großer Kopf mit Hörnern, Flügel mit Teamfarben-Band, Augen glühend.
- **Wither**: Drei Köpfe mit drei verschiedenen Gesichtsausdrücken (wütend, gelangweilt, irre).

## 14 · Navigator als Landkarte

- **Typ**: Landkarte auf Pergament, eingerahmt in Holz mit Eisenecken; Titelband „Karte des Reichs“.
- **Stil**: Handgezeichnet, Tinten-Konturen, Wasser blassgrün-blau, Wald als runde Baumtupfen, Berge als Dreiecke mit Schnee. Basis: landkarte_entwurf.png.
- **Orte**: Ankerplatz/Lobby (Mitte, Monument) · Kesseldorf (MineClash-Dorf) · Rosenarena (1v1) · Grenzlande (Krieg) · Kronstadt (CityBuild, Emblem Krone, Villager Gundula Grundmann; Klick öffnet die Instanzwahl CityBuild-1/-2/-3) · Nebelland (kommende Modi).
- **Ortsmarke**: 32×32 Emblem (Burg, Banner, Schwerter, Kompass, Krone) auf runder Pergament-Plakette mit Tinten-Kontur. Name darunter als Spruchband.
- **Zustände**: Normal: Emblem ruhig. Hover: Emblem animiert + Tooltip mit Spielerzahl. Voll: Banner rot. Wartung: Emblem grau + Bauzaun-Icon.
- **Villager**: Neben jeder Ortsmarke der zuständige Villager als Kopf-Item (Kampfmeister bei der Arena usw.).
- **Tooltip**: Name in Gold, Zeile „{online} Spieler“, ein Satz Lore in Pergament-dunkel, „Klick: Reisen“ in Gold.
- **Animation**: Pro Ort ein Emblem-Frame-Streifen (10 Frames, 3 Ticks). Wasser und Hintergrund statisch.
- **Klickfläche**: Jeder Ort belegt 2×2 Zellen; Orte so platzieren, dass ihre Zellen nicht kollidieren.

## 15 · Cosmetics

- **Stil**: Gleiche Regeln: kräftige Flächen, 1 px Kante, Licht oben links. Keine Halbtransparenz außer bei Heiligenschein-Glow.
- **Flügel**: Engel, Fee, Eis (frei) · Feuer, Dämon, Drache (Recht). Flügel schlagen langsam im Stand (3 s), schneller beim Sprinten.
- **Kronen**: Bronze, Silber, Gold. Gleiche Form, Material nach Stufe; Gold mit roten Edelsteinen.
- **Heiligenschein**: Weiß, Dunkel, Regenbogen. Schwebt 2 px über dem Kopf und dreht sich langsam.
- **Rarität**: Cosmetics nutzen dieselben Rarity-Farben wie Karten, sichtbar im Auswahlmenü als Kachelfarbe.
- **Vorschau**: Im Profilmenü auf einem drehenden Spielerkopf/Körper; Gesperrte mit Schloss-Badge und Hinweis, wie man sie bekommt.

## 16 · Bewegung und Sound

- **Grundsatz**: Bewegung zeigt Leben, nicht Ladezustand. Jedes Menü hat genau ein ruhiges Lebenszeichen (Kerze, Fackel, Feder).
- **Lebenszeichen**: Kerze/Fackel 8 Frames · 3 Ticks. Immer am Rand, nie neben Text.
- **Belohnung**: Edelstein an/aus, Wachssiegel prägt sich: 10–12 Frames · 2 Ticks, einmalig.
- **Warnung**: Warnrune pulsiert 6 Frames · 4 Ticks, nur solange der Zustand besteht.
- **Auswahl**: Kein Blinken. Ausgewählte Elemente bekommen Gold und bleiben ruhig.
- **Web**: Übergänge 120–180 ms, Ease-out; Knopf drückt 2 px; Einblenden von Panels mit 6 px Hub. Respektiert „reduzierte Bewegung“.
- **Sound Klick**: Holzklick (kurz, trocken) für alle Knöpfe.
- **Sound Erfolg**: Glocke/Münzen bei Kauf und Belohnung; Fanfare nur bei Sieg und Boss-Truhe.
- **Sound Fehler**: Dumpfer Holzschlag + Villager-„Hmm“.

### Eigenes Sound-Pack

- **Namespace**: Eigener Namespace im Netzwerk-Pack (z. B. reich:ui.click), wird mit den Addon-Packs gemergt. Vanilla-Sounds werden nicht überschrieben, außer gezielt (Villager-Laute).
- **Format**: OGG Vorbis, mono für alles im Raum (sonst kein 3D-Positionieren), stereo nur für Musik und UI. 44,1 kHz, Lautheit ca. −16 LUFS, UI-Sounds kürzer als 0,3 s.
- **UI**: Holzklick, Seite blättern, Pergament rascheln, Siegel prägen, Münzen, Fehler-Holzschlag. Je 3 Varianten, Minecraft wählt zufällig.
- **MineClash**: Karte ausspielen (Plopp), Turm-Treffer, Turm fällt, Truhe bereit (Glöckchen), Truhe öffnen, Sieg-Fanfare, Niederlage-Posaune, Energie-Countdown.
- **Ambiente**: Lobby-Platz, Dorf tags/nachts, Schmiede, Mine, Arena-Publikum. Als Loops mit 20–40 s Länge.
- **Musik**: Kurze Themen pro Ort (Lobby, Dorf, Arena, Grenzlande), dieselbe Melodie in verschiedenen Instrumentierungen (Laute, Blechbläser, Trommeln).
- **Kategorien**: Richtige Minecraft-Kategorie setzen (master/music/ambient/voice/neutral/hostile), damit Spieler die Lautstärke regeln können. Villager-Stimmen = voice.
- **Größe**: Ziel unter 25 MB für das Sound-Pack; Musik zuerst kürzen, wenn es zu groß wird.

### Villager-Stimme (TTS)

Der Vanilla-Client spielt nur Sounds aus dem Resourcepack; drei kombinierbare Stufen:

- **1 · Plapper-Stimme** (sofort machbar): Pro Villager ein Satz kurzer Silben-Sounds in seiner Tonlage (wie ein „Mümmel-Sprechen“). Während eine Textzeile läuft, spielt der Server Silbe für Silbe ab. Funktioniert für jeden Text, auch dynamische. Technik: 20–40 Silben-OGGs pro Stimme im Pack, Plugin spielt sie im Takt der Buchstaben mit leicht variierter Tonhöhe (pitch). Grenze: Keine echten Wörter, aber sehr viel Persönlichkeit; passt am besten zum Cartoon-Stil.
- **2 · Vorproduzierte Sätze** (empfohlen): Alle festen Villager-Sätze (Begrüßung, Tutorial, Shop, Sieg …) werden vorab mit einer TTS-Stimme pro Charakter erzeugt und ins Pack gelegt. Klingt wie echte Sprache. Technik: Texte aus den Übersetzungsdateien → TTS-Werkzeug (z. B. Piper lokal oder ein Cloud-Dienst) → OGG → Pack. Bei Textänderung automatisch neu erzeugen. Grenze: Nur feste Sätze, nichts mit Spielernamen. Pro Sprache eigene Dateien; Pack wird größer (ca. 30–60 KB pro Satz).
- **3 · Live-TTS** (nur mit Mod): Beliebiger Text inklusive Spielernamen wird live gesprochen. Technik: Über ein Client-Addon (LabyMod-Addon oder Fabric-Mod), das den Text bekommt und lokal vorliest, oder über eine Voice-Chat-Mod mit Server-Schnittstelle, die Audio an Spieler streamt. Grenze: Nur für Spieler mit der Mod. Vanilla-Spieler hören Stufe 1 oder 2 als Rückfall.

- **Empfehlung**: Stufe 1 überall + Stufe 2 für Tutorial und wichtige Sätze. Stufe 3 später als Bonus im LabyMod-Addon.
- **Stimmprofil**: Jeder Villager bekommt Tonhöhe, Tempo und Klangfarbe, passend zum Steckbrief: Bertold tief und langsam, Mira hell und schnell, Gorm laut, Klunk sehr tief und kurz, Knöpfchen hoch und fragend.
- **Villager-Laut**: Jede Zeile beginnt mit einem kurzen „Hmm“/„Hrm“ in der Tonlage des Charakters (Erkennungszeichen, erinnert an Vanilla).
- **Synchron**: Die Mund- und Nasenanimation (Abschnitt 12) läuft genau so lange wie der Sound.
- **Räumlich**: Stimmen sind mono und kommen vom Villager; Reichweite 12 Blöcke, Tutorial-Stimme ohne Abstand.
- **Abschaltbar**: Profil-Einstellung „Villager-Stimmen: an / nur Plappern / aus“. Text bleibt immer zusätzlich im Chat oder Hologramm.
- **Rechte**: Bei TTS-Diensten auf kommerzielle Nutzungsrechte der Stimmen achten; lokale Modelle mit freier Lizenz bevorzugen.

## 17 · Webpanel-Theme

Das lila Primary entfällt. Dunkelmodus (Holz) ist Standard, Hellmodus ist Pergament; Einstellung wird gespeichert.

```css
:root {            /* Hellmodus: Pergament */
  --background: #f1e2c2;  --foreground: #2a160a;
  --card: #fff8e6;        --card-foreground: #2a160a;
  --popover: #fff8e6;     --popover-foreground: #2a160a;
  --primary: #d8382e;     --primary-foreground: #ffffff;
  --secondary: #ffcf4a;   --secondary-foreground: #2a160a;
  --accent: #4ac85e;      --accent-foreground: #ffffff;
  --muted: #e6d2a8;       --muted-foreground: #5a3418;
  --destructive: #ff5a4a; --border: #2a160a; --input: #d8c08e; --ring: #ffcf4a;
  --success: #4ac85e; --warning: #ffb43a; --info: #5ab4ff;
  --chart-1: #d8382e; --chart-2: #ffcf4a; --chart-3: #4ac85e; --chart-4: #5ab4ff; --chart-5: #a05ae8;
  --radius: 0.875rem;
  --font-display: "Lilita One"; --font-sans: "Nunito"; --font-mono: "JetBrains Mono";
}
.dark {             /* Dunkelmodus: Holz (Standard) */
  --background: #2a1a10;  --foreground: #f1e2c2;
  --card: #3b2617;        --card-foreground: #f1e2c2;
  --popover: #3b2617;     --popover-foreground: #f1e2c2;
  --primary: #ffcf4a;     --primary-foreground: #2a160a;
  --secondary: #5a3418;   --secondary-foreground: #f1e2c2;
  --accent: #d8382e;      --accent-foreground: #ffffff;
  --muted: #4a3020;       --muted-foreground: #c9b89a;
  --destructive: #ff5a4a; --border: #1a0e06; --input: #5a3418; --ring: #ffcf4a;
}
```

- **Konturen**: Karten und Knöpfe 3 px Tinte (border), Tabellen und Eingaben 1 px.
- **Knöpfe**: shadcn Button-Varianten: default = primary mit Unterkante, secondary = Gold, outline = Pergament, destructive = Rot, ghost bleibt flach.
- **Schrift**: Überschriften und Knöpfe Lilita One, alles andere Nunito, Logs/Konsole/Code JetBrains Mono.
- **Seitenleiste**: Holz (#3b2617) in beiden Modi, aktiver Eintrag als Gold-Pill, Icons Lucide in Pergament.
- **Tabellen**: Kopfzeile Tinte mit Gold-Text, Zeilen abwechselnd card/muted, Hover Gold 15 %.
- **Status**: Online/Läuft = success, Startet = info, Pausiert = warning, Fehler/Gestoppt = destructive; immer als Pill mit Kontur.
- **Diagramme**: chart-1 bis chart-5 in dieser Reihenfolge, Linien 2.5 px, keine Flächenverläufe.
- **Login**: Pergament-Karte auf Holz, Wortmarke oben, Villager Knöpfchen als Illustration, Code-Eingabe als 6 einzelne Felder.

## 18 · LabyMod-Addon

- **HUD-Widgets**: Kleine Holz-Pills mit Tinten-Kontur, Icon + Zahl, Text Pergament. Abstand zum Bildschirmrand 4 px.
- **AFK-Screen**: Abgedunkelte Welt, Pergament-Zettel mittig mit Knöpfchen-Kopf und „Du bist eingenickt.“ + Zeit.
- **Admin-Panel**: Webpanel-Tokens im Dunkelmodus, kompakt (Abstände −25 %), Tabs als Gold-Pills.
- **LSS**: Tokens als LSS-Variablen mit denselben Namen wie im Webpanel (--primary, --border …).
- **Cosmetics**: Auswahl wie im Profilmenü: Kacheln in Rarity-Farbe.

## 19 · Logo und Wortmarke

- **Wortmarke**: [Name] in Lilita-artiger, dicker Cartoon-Schrift; Gold-Füllung, 3 px Tinten-Kontur, 4 px Schlagschatten. Ingame als eigene Pixel-Glyph (max. 128×32).
- **Bildmarke**: Ein Wappen/Schild (Form aus navigator/shield.png) mit dem Zentrum der Lore als Motiv. Wird erst gestaltet, wenn der Name feststeht.
- **Varianten**: Voll (Bildmarke + Wortmarke), nur Wortmarke, nur Bildmarke (Favicon, Tab-Icon, Discord).
- **Größen**: Ingame 16, 32, 64 px Höhe; Web min. 24 px Höhe.
- **Schutzraum**: Mindestens eine Konturbreite × 4 rundum.
- **Hintergründe**: Auf Holz, Pergament und Band-Rot; nie auf Fotos ohne Holzplakette dahinter.
- **Slogan**: Kurz, im Ton der Villager. Vorschläge folgen mit dem Namen.

## 20 · Do und Don’t

**Do**

- Nur Palette-Farben verwenden
- Jede Fläche mit Tinten-Kontur
- Licht immer von oben links
- Leere Zustände mit Villager-Spruch
- Gefahr-Aktionen immer mit Rückfrage
- Klickflächen exakt auf 18-px-Zellen
- Neue Elemente zuerst hier eintragen

**Don’t**

- Kein Lila Primary mehr, kein „modern dunkel“
- Keine weichen Verläufe oder Weichzeichner-Schatten
- Keine Schrift außer Minecraft-Font ingame
- Kein dauerhaftes Blinken
- „Helix“ nie spielersichtbar
- „Episch“ nicht verwenden, nur „Elite“
- Keine realistisch-gruseligen Mob-Gesichter

## 21 · Offene Entscheidungen

- [ ] Name des Netzwerks und des Reichs (ersetzt [Name])
- [ ] Zentrum der Lore (ersetzt Arbeitstitel „Ankerstein“) und damit die Bildmarke
- [ ] Spieler-Ränge (Premium/VIP) neben den Team-Rängen (CityBuild nutzt bis dahin Platzhalter-Stufen `citybuild.tier.1..3`)
- [ ] Ob Villager-Rede im Chat erscheint oder nur als Hologramm über dem Villager
- [ ] Eigene Ingame-Schrift für Titel (Version 2)
- [ ] TTS-Werkzeug und Stimmen für Stufe 2 auswählen

## Abweichungen

- **Stufe A (Text/Chat)**: Solange die Wortmarke als Glyph fehlt, zeigen Tablist-Kopf und Scoreboard-Titel den Netzwerknamen fett in Gold (`<#ffcf4a><bold>{network}`). Der Netzwerkname ist Arbeitstitel „Kronland“ (`network.name` in `node.toml` bzw. Panel); der Prefix nutzt `{network}` und folgt dem Namen automatisch.
- **Stufe A**: Die Befehle `/helix language` (Node) und `/helix link` (Discord-Bot) heißen technisch noch „helix“ und tauchen deshalb in Hinweistexten auf; ein Umbenennen der Befehle ist ein eigener Schritt.
- **Stufe A**: Der Team-Chat (`[Team]`) zeigt noch keinen Rang-Tag des Absenders, weil das Addon dafür keinen Platzhalter hat.
- **§6 Titelband**: Text im Fenstertitel lässt sich technisch nicht über y = 5 (Container-Pixel) setzen (Font-Ascent ≤ Höhe). Das Band liegt deshalb oben am Pergament (y 1–17) statt 6 px über dem Rahmen.
- **§6 Pergament-Buch**: Klickbar ist nur das 9×10-Raster, die Fläche ist fix 384 px. Die Liste liegt deshalb auf einer großen Pergamentseite über dem Raster, das Detail als Infozettel mit Siegel rechts, links ein Schreibpult (Bücherstapel, Tintenfass) mit dem Lebenszeichen. Fußzeile: Zurück-Pfeil (Spalte 0) · Knopf 36 px (Spalten 1–2) · Pfeil · Seite · Pfeil (3–5) · Knopf 54 px (6–8). Längere Knopftexte werden gekürzt (z. B. „Brief“ statt „Schreiben“).
- **§14 Navigator**: Vanilla kennt keinen Hover-Zustand für Items; die Embleme animieren deshalb ruhig und dauerhaft statt nur beim Hover. Orte: Ankerplatz, Kesseldorf, Rosenarena, Grenzlande, Nebelland, Lichtung (Reserve); Zuordnung per Panel (Ort, Villager, Lore, Wartung) oder aus dem Task-Namen geraten.
- **§11 Boss-Karten**: Die Glüh-Animation läuft nur am 3D-Karten-Item; in Schrift-Glyphen (Kartentisch, Kampfleiste) sind Karten statisch, weil Fonts nicht animieren.
- **§11 Energie**: Die Energie-Leiste ersetzt die XP-Leiste im gesamten Netzwerk-Pack (Diamant-Blau).
- **§13 Truppen**: Mobs werden als Display-Rigs (Blockbench-Modelle, Teamteil per Dye-Tint) über einer unsichtbaren KI-Entität dargestellt.
- **Farben**: Hautfarben der Villager (`#c99872`/`#b9835e`/`#e0b48e`) und einzelne Mob-Grundfarben stehen nicht in §3 — als Token „skin“ bzw. mob-eigene Grundfarbe (§13 erlaubt sie) zu führen.
- **§13 Enderdrache** (Owner-Entscheidung 2026-10-04): Der Drache ist als einziger Mob realistischer statt Cartoon gestaltet – Proportionen am Vanilla-Drachen (langer Hals und Schwanz, schmaler Kopf, große Flügel), feine Schuppen- und Verlaufstexturen ohne harte Konturen.
- **§14 Kronstadt**: Der Ort liegt oben mittig zwischen Kesseldorf und Rosenarena (Zellen 3–4/1–2, Kopf in Spalte 5), das Kartenmotiv (Häuser und Turm mit Goldfahne) ist vorerst per Skript gemalt statt in Blockbench. Neben der Ortsmarke steht Gundula Grundmanns Kopf. Ein Klick öffnet die Instanzwahl (Pergament-Buch mit Status-Pills grün offen / gold ab 80 % / rot voll / grau Wartung).
- **§12 CityBuild-Figuren** (2026-10-16): Die zehn Figuren (`lotte`, `gundula`, `fritz`, `ludwig`, `henni`, `waldemar`, `rupert`, `paula`, `brunhilde`, `greta`) sind nicht in Blockbench gebaut, weil das Blockbench-MCP nicht verbunden war. Sie entstehen per Skript (`tools/villagers/citybuild.py`) aus dem Model-Sheet des Casts: gleiche Körper-Geometrie und Pivots, eigene Farben, Requisiten als Würfel, Flächen flach nach §4 (Seite basis, oben hell, unten dunkel, 1 px Kante). Die Sprecherköpfe 16/32 px rendert dasselbe Skript als Frontansicht mit Tinten-Silhouette. Feinere Mimik und Requisiten sollten später in Blockbench nachgearbeitet werden; das Skript überschreibt die Dateien bei jedem Lauf.
- **§12 Robenfarben**: Robenfarben sind keine §3-Tokens, sondern stehen pro Figur in `VillagerCast` (helix-addon-npc-paper) und färben den Sprechernamen. CityBuild: Lotte `#5aa8e0`, Gundula `#5a6470`, Fritz `#e07a2a`, Ludwig `#2e6a40`, Henni `#9a4a32`, Waldemar `#5a7a3a`, Rupert `#a8783a`, Paula `#f0c030`, Brunhilde `#7a7f88`, Greta `#6e3a4a`.
- **§16 Stimmen**: Die CityBuild-Figuren haben eigene Plapper-Stimmen aus `tools/sounds/synth.mjs`, zum Beispiel Waldemar langsam und tief, Paula hastig und hoch, Greta leise und flüsternd.
