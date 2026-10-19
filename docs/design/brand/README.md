# KronlandMC – Bildmarke „Kronen-Siegel“

Owner-Entscheidung vom 19.10.2026: Die Bildmarke ist Entwurf A, das Kronen-Siegel. In der Bildmarke steht kein „MC“. Details stehen in DESIGN.md §19.

| Datei | Größe | Verwendung |
|---|---|---|
| `logo256.png` | 256×256 | Web, Discord, Store, Social; größere Ausgaben nur in ganzzahligen Vielfachen (512, 1024), Skalierung ohne Glättung (Nearest Neighbour) |
| `icon64.png` | 64×64 | Minecraft-Serverliste (MOTD-Addon-Standard), Webpanel-Logo bis 64 px |
| `favicon32.png` | 32×32 | Browser-Favicon, Tab-Icon (vereinfacht: ohne Edelstein-Spitzen und Randpunkte) |
| `favicon16.png` | 16×16 | Kleinstes Favicon (nur Krone auf roter Münze) |

- **Mindestgröße:** 16 px. Unter 32 px immer die Favicon-Varianten nehmen und nicht das große Logo verkleinern.
- **Schutzraum:** rundum mindestens 1/8 der Kantenlänge (bei 256 px also 32 px) frei von Text und anderen Marken.
- **Hintergründe:** Holz, Pergament, Band-Rot sowie neutrale helle und dunkle Flächen; der Goldrand trägt die Marke auf beiden. Nie auf unruhigen Fotos ohne Holzplakette dahinter.
- **Nicht erlaubt:**
  - Schrift oder „MC“ in das Siegel setzen.
  - Farben außerhalb der Palette (§3) verwenden.
  - Die Marke weichzeichnen oder mit Glättung skalieren.
  - Die Marke drehen.
- **Quelle:** `logo-source.js` ist der Zeichencode (Funktion `drawA`). Gezeichnet wurde in Blockbench über die Canvas-Helfer in `/workspace/.bbshots/logo/bb.js`.
