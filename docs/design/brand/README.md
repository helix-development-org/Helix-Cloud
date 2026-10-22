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

## Banner & Schriftzug (Owner-Entscheidung 19.10.2026)

Schriftzug **V3 „Modern & clean“**: „KRONLAND“ in Lilita One, Goldverlauf (`#c77a06` → `#ffcf45` → `#fff1b0`), dünne Tinten-Kontur, dezenter Glow; Untertitel „MINECLASH · CITYBUILD“ in Lilita One, Pergament-hell. Hintergrund: Nacht-Panorama „Burg auf der Klippe“ (Kronstadt auf der Felsinsel, Leuchtturm, Vollmond, Blau + Gold). Gerendert in Blender (Cycles, 128 Samples); Quellen und Skripte liegen außerhalb des Repos unter `.bbshots/banner-final/` (`kronland_banner_final.blend`) bzw. `.bbshots/banner-panorama/build_panorama.py` und `.bbshots/schriftzug/build_schriftzug.py`.

| Datei (`banner/`) | Größe | Verwendung |
|---|---|---|
| `banner_1920x640.png` | 1920×640 | Website-Header, Forum, allgemeiner Header |
| `discord_960x540.png` | 960×540 | Discord-Server-Banner (16:9) |
| `x_1500x500.png` | 1500×500 | X/Twitter-Header (unten links bleibt frei fürs Profilbild) |
| `youtube_2560x1440.png` | 2560×1440 | YouTube-Kanalbild; Schriftzug und Burg liegen in der sicheren Mitte 1546×423 |
| `social_1080.png` | 1080×1080 | Quadratische Posts (Instagram, Discord-Ankündigungen) |
| `logo_kronland_v3_alpha.png` | 2048×480 | Freigestellter Schriftzug mit Glow (auf dunklen Flächen) |
| `logo_kronland_v3_alpha_noglow.png` | 2048×480 | Freigestellter Schriftzug ohne Glow (auf hellen Flächen, Druck) |

- Der Schriftzug wird nie verzerrt oder umgefärbt; Untertitel nur zusammen mit dem Titel.
- Banner sind gerenderte Bilder – nicht nachträglich weichzeichnen oder neu einfärben, sondern bei Änderungen neu rendern.
