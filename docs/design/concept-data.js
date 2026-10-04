
class Component extends DCLogic {
  renderVals() {
    const toc = ['Leitidee','Lore','Farben','Form','Typo','Menü-Raster','Komponenten','Icons','Chat','HUD','MineClash','Villager','Mobs','Navigator','Cosmetics','Bewegung','Webpanel','LabyMod','Logo','Do/Don’t','Offen'].map((n,i)=>({n:(i+1)+' '+n,id:'s'+(i+1)}));
    const C = (n,tok,l,b,d,use)=>({n,tok,l,b,d,use});
    return {
      toc,
      principles: [
        {t:'Spielzeug, nicht Museum',d:'Alles soll anfassbar wirken: dicke Kontur, Glanzkante, dunkle Unterkante. Lieber eine Form zu viel betont als zu flach.'},
        {t:'Holz trägt, Pergament informiert',d:'Rahmen und Hintergründe sind Holz. Lesbare Inhalte stehen auf Pergament. Diese Trennung gilt in jedem Menü.'},
        {t:'Rot ruft, Gold belohnt',d:'Band-Rot markiert Titel und Gefahr, Gold markiert Auswahl und Belohnung. Grün bestätigt.'},
        {t:'Villager sprechen',d:'Wo ein Systemtext stehen könnte, darf ein Villager sprechen: Begrüßung, leere Zustände, Fehler, Tutorial.'},
        {t:'Eine Kreatur, ein Merkmal',d:'Jeder Mob und Villager hat ein übertriebenes Erkennungsmerkmal, das auch als 16×16-Icon lesbar bleibt.'},
        {t:'Ein System überall',d:'Ingame, Web und LabyMod nutzen dieselben Tokens und Namen. Keine Sonderfarben pro Addon.'}
      ],
      lore: [
        {k:'Das Reich',v:'[Name des Reichs]: ein Königreich aus Tälern, Dörfern und Arenen. Der Spieler kommt als Neuling aus der „Oberwelt“ an.'},
        {k:'Das Zentrum',v:'[Zentrum – Arbeitstitel offen]: ein Ort oder Objekt in der Mitte der Lobby, um das sich die Welt ordnet. Visuell: Monument mit Gold und warmem Licht.'},
        {k:'Die Villager',v:'Das Volk des Reichs. Sie verwalten, handeln, schmieden und richten Turniere aus. Sie halten sich für sehr wichtig.'},
        {k:'Die Große Bindung',v:'Ein altes Ereignis, seit dem Mobs in Karten gebunden sind und nur für den kämpfen, der ihre Karte hält. Begründet MineClash.'},
        {k:'Die Arenen',v:'Turnierplätze. Siege bringen Rosen, das Ehrenzeichen des Reichs.'},
        {k:'Die Grenzlande',v:'Wildnis jenseits der Mauern (Kriegs-Server). Dort streifen ungebundene Mobs, die man als Karte einfangen kann.'},
        {k:'Währungen',v:'Smaragde = Alltagsgeld der Villager · Diamanten = seltene Kostbarkeit · Rosen = Ehre, nur durch Kämpfe.'},
        {k:'Neue Modi',v:'Erscheinen als neue Orte auf der Landkarte, vorher als Nebelfläche angedeutet.'}
      ],
      palettes: [
        {title:'Materialien', items:[
          C('Holz','wood','#7a4a24','#5a3418','#3b2617','Rahmen, Menühintergrund, Dunkelmodus'),
          C('Pergament','parchment','#fffaf0','#f1e2c2','#d8c08e','Inhaltsflächen, Infozettel, Hellmodus'),
          C('Tinte','ink','#4a2e1c','#2a160a','#1a0e06','Kontur, Text auf Pergament, Schatten'),
          C('Eisen','iron','#d6dae0','#a8adb6','#6e737c','Beschläge, Ecken, Scharniere'),
          C('Tischdecke','cloth','#c0302a','#8e2a1a','#5a160e','Spielflächen (Kartentisch, Arenatafel)')
        ]},
        {title:'Akzente', items:[
          C('Band-Rot','ribbon','#ff7a6a','#d8382e','#9a2018','Titelband, Gefahr, Benachrichtigungs-Badge'),
          C('Gold','gold','#fff0a8','#ffcf4a','#e0a420','Auswahl, aktiv, Belohnung, Rang-Prefix'),
          C('Smaragd','emerald','#8cf09a','#4ac85e','#2a8a3e','Bestätigen, Erfolg, Smaragd-Währung'),
          C('Diamant','diamond','#a8dcff','#5ab4ff','#2f7ad0','Premium, Info, Diamant-Währung'),
          C('Rose','rose','#ffb0c0','#ff6a8a','#c8385a','Rosen-Währung, Rang/Trophäen')
        ]},
        {title:'Status', items:[
          C('Info','info','#a8dcff','#5ab4ff','#2f7ad0','Hinweise, Tipps'),
          C('Erfolg','success','#8cf09a','#4ac85e','#2a8a3e','Erfolgreiche Aktion'),
          C('Warnung','warn','#ffe08a','#ffb43a','#c87a10','Achtung, Bestätigung nötig'),
          C('Fehler','error','#ff9a8a','#ff5a4a','#b8261a','Fehler, Verbote'),
          C('Gesperrt','disabled','#c8bca8','#a89a84','#6e6252','Inaktive Knöpfe, gesperrte Inhalte')
        ]},
        {title:'Seltenheiten', items:[
          C('Normal','rarity.normal','#e2e6ec','#b8c0cc','#7a8494','Karten, Truhen, Rahmen'),
          C('Besonders','rarity.special','#a8dcff','#5ab4ff','#2f7ad0','„Besonders“'),
          C('Elite','rarity.elite','#d2a8ff','#a05ae8','#6a2ab0','ersetzt „Episch“'),
          C('Titan','rarity.titan','#fff0a8','#ffcf4a','#e0a420','Gold mit Ketten-Motiv'),
          C('Boss','rarity.boss','#ff9a8a','#e8403a','#9a2018','Rot mit Glüh-Animation')
        ]},
        {title:'Teams (MineClash)', items:[
          C('Team Blau','team.blue','#8ab8ff','#3a6ae0','#1c3a8a','eigene Seite, Türme, Banner'),
          C('Team Rot','team.red','#ff8a7a','#d8382e','#7a1a12','Gegnerseite, Türme, Banner')
        ]}
      ],
      formRules: [
        {k:'Kontur',ig:'1 px Tinte um jede Fläche, 2 px um Knöpfe und Rahmen',web:'3–4 px #2a160a, Tabellen 1 px'},
        {k:'Ecken',ig:'Abgerundet durch weggelassene Eck-Pixel (1 px bei klein, 2 px Stufe bei Rahmen)',web:'Radius 12–14 px (Knöpfe), 18–22 px (Panels), 999 px (Pills)'},
        {k:'Glanzkante',ig:'1 px Reihe „hell“ oben innen',web:'inset 0 4px 0 hell'},
        {k:'Unterkante',ig:'2 px Reihe „dunkel“ unten innen',web:'inset 0 -6px 0 dunkel'},
        {k:'Schlagschatten',ig:'1 px Tinte unter Panels (versatz y+1); Text mit Minecraft-Schatten',web:'0 4–8px 0 Tinte, kein Weichzeichner'},
        {k:'Licht',ig:'Immer von oben links; Glanzpixel oben links an runden Objekten',web:'identisch'},
        {k:'Schattierung',ig:'Max. 3 Stufen pro Farbe + Kontur, kein Dithering auf Knöpfen; Rauschen nur auf Holz/Pergament, sparsam',web:'flache Flächen, keine Verläufe außer Glow bei Boss'},
        {k:'Gedrückt',ig:'Glanzkante weg, Inhalt 1 px nach unten, Unterkante 1 px',web:'translateY(2px), Unterkante halbiert'},
        {k:'Abstände',ig:'Raster 2 px; Innenabstand Panel 6 px, zwischen Gruppen 4 px',web:'Raster 4 px; 8 / 12 / 16 / 24 / 32'}
      ],
      type: [
        {k:'Ingame',v:'Ausschließlich Minecraft-Standardschrift (8 px Zeilenhöhe). Keine eigene Schrift im Resourcepack, bis Version 2 des Konzepts.'},
        {k:'Ingame Titel',v:'Standardschrift, Gold (#ffcf4a) mit Tinte-Schatten auf Band-Rot; Wörter in Großbuchstaben am Wortanfang, kein KOMPLETT-GROSS.'},
        {k:'Ingame Text',v:'Tinte (#2a160a) ohne Schatten auf Pergament; Pergament (#f1e2c2) mit Schatten auf Holz.'},
        {k:'Web Display',v:'Lilita One (Google Fonts), für Titel, Knöpfe, Badges. Titel mit 2 px Tinten-Stroke und 4 px Schlagschatten.'},
        {k:'Web Text',v:'Nunito 500/700/800, Fließtext 15–16 px, Zeilenhöhe 1.5–1.65.'},
        {k:'Web Code',v:'JetBrains Mono 12–13 px für Befehle, Logs, Konsole, IDs.'},
        {k:'Größen Web',v:'56 / 34 / 22 / 18 / 16 / 14 / 12 px. Unter 12 px nichts.'},
        {k:'Zahlen',v:'Tausender mit Punkt (1.240), Zeiten als 1 h 20 min, Countdown als 08:12.'}
      ],
      grid: [
        {k:'Fläche',v:'384×256 px, als Font-Glyphen gekachelt (max. 256 px pro Glyph).'},
        {k:'Außenrahmen',v:'8 px Holzrahmen mit Eisenecken (12×12) an allen vier Ecken; 1 px Tinte außen.'},
        {k:'Titelband',v:'Mittig oben, ragt 6 px über den Rahmen; Höhe 16 px, Textzeile mittig.'},
        {k:'Inhaltszonen',v:'Links Liste/Sammlung, Mitte Aktion, rechts Infozettel. Zonen immer an Zell-Spalten ausrichten.'},
        {k:'Zellen',v:'Klickbare Elemente sind 18×18 oder Vielfache; Grafik eines Knopfs deckt die Zellen exakt ab, damit Hover-Rahmen und Grafik übereinstimmen.'},
        {k:'Hover',v:'Minecraft-Hover-Rahmen bleibt. Zusätzlich darf ein Item-Wechsel einen Gold-Rahmen zeigen.'},
        {k:'Tooltip',v:'Erste Zeile Name in Token-Farbe, zweite Zeile Kategorie in Grau, danach Werte, letzte Zeile Aktion in Gold („Klick: Wählen“).'},
        {k:'Navigation',v:'Zurück immer unten links (Pfeil links), Schließen nie als eigener Knopf (ESC). Blättern unten mittig: Pfeil · Seitenzahl · Pfeil.'}
      ],
      menuTypes: [
        {t:'Pergament-Buch',for:'Briefe, Profil, Übersetzungen, Anticheat, Freunde, Clans',d:'Holzpult mit aufgeschlagenem Buch, linke Seite Liste, rechte Seite Detail. Basis: ledger/background.png, neu mit Tinten-Kontur.'},
        {t:'Tisch',for:'Kartentisch, Shop, Schmied, Forscher',d:'Draufsicht auf Tisch mit Tischdecke. Gegenstände liegen darauf; Infozettel rechts.'},
        {t:'Landkarte',for:'Navigator',d:'Pergamentkarte auf Holz, Orte als animierte Embleme (Abschnitt 14).'},
        {t:'Tafel',for:'Truhen, Clan-Krieg, Ranglisten',d:'Holztafel mit Bannern links/rechts in Teamfarben, Pergament-Aushang in der Mitte.'}
      ],
      components: [
        {n:'Rahmen',d:'Holz 8 px mit Eisenecken. Innenkante 1 px Gold bei wichtigen Menüs (Shop, Truhen).',spec:'wood + iron · Kontur ink · innen gold.base 1px'},
        {n:'Titelband',d:'Rotes Band mit eingerollten Enden, Goldkante oben und unten. Text Gold mit Schatten.',spec:'ribbon.base · Kante gold.base 1px · Höhe 16px'},
        {n:'Knopf primär',d:'Smaragd-Grün für Bestätigen/Wählen/Kaufen.',spec:'emerald · min. 36×18 px (2 Zellen) · Text weiß + Schatten'},
        {n:'Knopf aktiv',d:'Gold für ausgewählte Optionen und Tabs.',spec:'gold · Text ink ohne Schatten'},
        {n:'Knopf sekundär',d:'Pergament für Abbrechen, Zurück, neutrale Aktionen.',spec:'parchment · Text ink'},
        {n:'Knopf Gefahr',d:'Band-Rot für Entfernen, Bannen, Auflösen. Immer mit Rückfrage.',spec:'ribbon · Text weiß + Schatten'},
        {n:'Knopf gesperrt',d:'Grau, Kontur grau statt Tinte, kein Glanz. Tooltip erklärt warum.',spec:'disabled · Kontur disabled.dark'},
        {n:'Pfeile',d:'Holzpfeile mit Goldspitze, 18×18. Gesperrt: grau.',spec:'ledger/arrow_left/right überarbeiten'},
        {n:'Tabs',d:'Lesezeichen am Buchrand. Aktiv: Gold und 2 px länger, inaktiv: Holz hell.',spec:'18×24 px · max. 6 Tabs'},
        {n:'Listenzeile',d:'Pergamentstreifen, Kopf/Icon links (16×16), Name, rechts Status-Badge. Ausgewählt: Gold-Fläche.',spec:'Höhe 18 px · Trennlinie parchment.dark gestrichelt'},
        {n:'Badge',d:'Pill mit Kontur. Rot für Anzahl Neu, Grün für Online, Grau für Offline, Gold für Rang.',spec:'Höhe 9 px ingame · Text 1 Zeile'},
        {n:'Infozettel',d:'Pergament mit Reißzwecke oder Siegel oben. Für Details einer Auswahl.',spec:'parchment · Siegel ribbon.base'},
        {n:'Kachel',d:'Quadratisch, Rarity-Farbe als Fläche, Inhalt-Icon mittig, Titel darunter.',spec:'36×36 oder 54×54 px'},
        {n:'Leerer Zustand',d:'Immer ein Villager-Kopf + ein Satz in seiner Stimme, nie nur „Keine Einträge“.',spec:'z. B. Briefe leer: Knöpfchen „Noch keine Post. Niemand mag dich. Spaß!“'},
        {n:'Fortschritt',d:'Holzrinne, Füllung in Token-Farbe mit Glanzkante; Zahl rechts daneben.',spec:'Höhe 6 px · Truhen-Countdown: diamond'},
        {n:'Eingabe',d:'Amboss-/Schild-Eingabe bleibt Minecraft-nativ; davor ein Hinweis im Pergament-Stil.',spec:'—'}
      ],
      icons: [
        {k:'Größen',v:'16×16 für Items und Inline-Icons, 32×32 für Menü-Embleme und Kacheln, 64×64 nur für Karten.'},
        {k:'Kontur',v:'1 px Tinte rundum (auch bei 32×32), Ecken der Kontur weglassen für weichere Form.'},
        {k:'Licht',v:'Oben links. Ein Glanzpixel (oder 2×2 bei 32 px) in „hell“.'},
        {k:'Farben',v:'Max. 3 Stufen pro Farbe + Tinte. Nur Palette-Farben aus Abschnitt 3.'},
        {k:'Silhouette',v:'Muss als einfarbige Fläche erkennbar sein; Test: Icon in Tinte füllen und prüfen.'},
        {k:'Hintergrund',v:'Transparent. Kein Rahmen im Icon selbst; Rahmen kommt von der Komponente.'},
        {k:'Karten 64×64',v:'4 px Rarity-Rahmen mit Tinten-Kontur, Bildfeld mit Mob-Kopf in Frontalansicht, Kosten-Tropfen unten links (Diamant-Blau mit Zahl), Name nicht auf der Karte (steht im Tooltip).'},
        {k:'Truhen',v:'Je Seltenheit eigene Farbe der Beschläge, Form gleich. Kriegstruhen mit Banner in Teamfarbe.'},
        {k:'Animiert',v:'Frame-Streifen 16 px breit, 8–12 Frames, 2–4 Ticks pro Frame. Nur für Embleme, Kerzen, Fackeln, Edelsteine.'}
      ],
      chatDemo: [
        {p:'[Name] »',pc:'#ffcf4a',t:'Willkommen im Reich, Spieler!',c:'#f1e2c2'},
        {p:'[Name] »',pc:'#ffcf4a',t:'Hinweis: Deine Truhe ist in 08:12 bereit.',c:'#5ab4ff'},
        {p:'[Name] »',pc:'#ffcf4a',t:'Karte gekauft: Hexe',c:'#4ac85e'},
        {p:'[Name] »',pc:'#ffcf4a',t:'Achtung: Dein Deck ist fast voll.',c:'#ffb43a'},
        {p:'[Name] »',pc:'#ffcf4a',t:'Dafür hast du keine Rechte.',c:'#ff5a4a'},
        {p:'Mira Feilsch:',pc:'#4ac85e',t:'Für dich? Sonderpreis. Also der normale.',c:'#f1e2c2'},
        {p:'[Mod] Steve »',pc:'#ffb43a',t:'gg!',c:'#f1e2c2'}
      ],
      chat: [
        {k:'Prefix',v:'[Name] in Gold, danach » in Holz-hell. Kurzform für Actionbar/Scoreboard: Initiale in Gold.',mm:'<#ffcf4a>[Name]</#ffcf4a> <#7a4a24>»</#7a4a24> '},
        {k:'Standardtext',v:'Pergament. Hervorhebungen (Namen, Zahlen) in Gold.',mm:'<#f1e2c2>Du hast <#ffcf4a>30 Rosen</#ffcf4a> erhalten.'},
        {k:'Info',v:'Diamant-Blau, beginnt mit „Hinweis:“ nur wenn nötig.',mm:'<#5ab4ff>…'},
        {k:'Erfolg',v:'Smaragd-Grün, kurz, Vergangenheitsform.',mm:'<#4ac85e>Karte gekauft: <#ffcf4a>Hexe'},
        {k:'Warnung',v:'Orange, beginnt mit „Achtung:“.',mm:'<#ffb43a>Achtung: …'},
        {k:'Fehler',v:'Rot, sagt was zu tun ist, nicht nur was falsch ist.',mm:'<#ff5a4a>Das Deck ist voll. Entferne erst eine Karte.'},
        {k:'Villager-Rede',v:'Name in Robenfarbe des Villagers mit Doppelpunkt, Text Pergament, kein Prefix. Max. 2 Zeilen, dann Pause.',mm:'<#4ac85e>Mira Feilsch:</#4ac85e> <#f1e2c2>…'},
        {k:'Klickbar',v:'Gold, unterstrichen nur beim Hover; Hover-Text erklärt die Aktion.',mm:'<click:run_command:/mc deck><hover:show_text:"Kartentisch öffnen"><#ffcf4a>[Deck öffnen]'},
        {k:'Staff-Chat',v:'Prefix [Team] in Rot, danach Rang-Tag des Absenders (Owner, Manager, Developer, Admin, Mod, Support), Inhalt Pergament.',mm:'<#d8382e>[Team]</#d8382e> …'},
        {k:'Spieler-Chat',v:'{prefix}{color}{name}{suffix} » {message}; Pfeil in Holz-hell, Nachricht Pergament.',mm:'{prefix}<color>{name}</color> <#7a4a24>»</#7a4a24> <#f1e2c2>{message}'}
      ],
      ranks: [
        {tag:'[Owner]',c:'#d8382e',w:100},{tag:'[Manager]',c:'#ff6a8a',w:95},{tag:'[Developer]',c:'#a05ae8',w:90},
        {tag:'[Admin]',c:'#ff5a4a',w:80},{tag:'[Mod]',c:'#ffb43a',w:70},{tag:'[Support]',c:'#4ac85e',w:60},
        {tag:'[Spieler]',c:'#c8bca8',w:0}
      ],
      soundPack: [
        {k:'Namespace',v:'Eigener Namespace im Netzwerk-Pack (z. B. reich:ui.click), wird mit den Addon-Packs gemergt. Vanilla-Sounds werden nicht überschrieben, außer gezielt (Villager-Laute).'},
        {k:'Format',v:'OGG Vorbis, mono für alles im Raum (sonst kein 3D-Positionieren), stereo nur für Musik und UI. 44,1 kHz, Lautheit ca. −16 LUFS, UI-Sounds kürzer als 0,3 s.'},
        {k:'UI',v:'Holzklick, Seite blättern, Pergament rascheln, Siegel prägen, Münzen, Fehler-Holzschlag. Je 3 Varianten, Minecraft wählt zufällig.'},
        {k:'MineClash',v:'Karte ausspielen (Plopp), Turm-Treffer, Turm fällt, Truhe bereit (Glöckchen), Truhe öffnen, Sieg-Fanfare, Niederlage-Posaune, Energie-Countdown.'},
        {k:'Ambiente',v:'Lobby-Platz, Dorf tags/nachts, Schmiede, Mine, Arena-Publikum. Als Loops mit 20–40 s Länge.'},
        {k:'Musik',v:'Kurze Themen pro Ort (Lobby, Dorf, Arena, Grenzlande), dieselbe Melodie in verschiedenen Instrumentierungen (Laute, Blechbläser, Trommeln).'},
        {k:'Kategorien',v:'Richtige Minecraft-Kategorie setzen (master/music/ambient/voice/neutral/hostile), damit Spieler die Lautstärke regeln können. Villager-Stimmen = voice.'},
        {k:'Größe',v:'Ziel unter 25 MB für das Sound-Pack; Musik zuerst kürzen, wenn es zu groß wird.'}
      ],
      tts: [
        {n:'1 · Plapper-Stimme',badge:'sofort machbar',bc:'#8cf09a',d:'Pro Villager ein Satz kurzer Silben-Sounds in seiner Tonlage (wie ein „Mümmel-Sprechen“). Während eine Textzeile läuft, spielt der Server Silbe für Silbe ab. Funktioniert für jeden Text, auch dynamische.',tech:'20–40 Silben-OGGs pro Stimme im Pack, Plugin spielt sie im Takt der Buchstaben mit leicht variierter Tonhöhe (pitch).',limit:'Keine echten Wörter, aber sehr viel Persönlichkeit; passt am besten zum Cartoon-Stil.'},
        {n:'2 · Vorproduzierte Sätze',badge:'empfohlen',bc:'#ffe08a',d:'Alle festen Villager-Sätze (Begrüßung, Tutorial, Shop, Sieg …) werden vorab mit einer TTS-Stimme pro Charakter erzeugt und ins Pack gelegt. Klingt wie echte Sprache.',tech:'Texte aus den Übersetzungsdateien → TTS-Werkzeug (z. B. Piper lokal oder ein Cloud-Dienst) → OGG → Pack. Bei Textänderung automatisch neu erzeugen.',limit:'Nur feste Sätze, nichts mit Spielernamen. Pro Sprache eigene Dateien; Pack wird größer (ca. 30–60 KB pro Satz).'},
        {n:'3 · Live-TTS',badge:'nur mit Mod',bc:'#ffb0c0',d:'Beliebiger Text inklusive Spielernamen wird live gesprochen.',tech:'Über ein Client-Addon (LabyMod-Addon oder Fabric-Mod), das den Text bekommt und lokal vorliest, oder über eine Voice-Chat-Mod mit Server-Schnittstelle, die Audio an Spieler streamt.',limit:'Nur für Spieler mit der Mod. Vanilla-Spieler hören Stufe 1 oder 2 als Rückfall.'}
      ],
      voiceRules: [
        {k:'Empfehlung',v:'Stufe 1 überall + Stufe 2 für Tutorial und wichtige Sätze. Stufe 3 später als Bonus im LabyMod-Addon.'},
        {k:'Stimmprofil',v:'Jeder Villager bekommt Tonhöhe, Tempo und Klangfarbe, passend zum Steckbrief: Bertold tief und langsam, Mira hell und schnell, Gorm laut, Klunk sehr tief und kurz, Knöpfchen hoch und fragend.'},
        {k:'Villager-Laut',v:'Jede Zeile beginnt mit einem kurzen „Hmm“/„Hrm“ in der Tonlage des Charakters (Erkennungszeichen, erinnert an Vanilla).'},
        {k:'Synchron',v:'Die Mund- und Nasenanimation (Abschnitt 12) läuft genau so lange wie der Sound.'},
        {k:'Räumlich',v:'Stimmen sind mono und kommen vom Villager; Reichweite 12 Blöcke, Tutorial-Stimme ohne Abstand.'},
        {k:'Abschaltbar',v:'Profil-Einstellung „Villager-Stimmen: an / nur Plappern / aus“. Text bleibt immer zusätzlich im Chat oder Hologramm.'},
        {k:'Rechte',v:'Bei TTS-Diensten auf kommerzielle Nutzungsrechte der Stimmen achten; lokale Modelle mit freier Lizenz bevorzugen.'}
      ],
      hud: [
        {k:'Actionbar',v:'Währungen dauerhaft: Icon + Zahl, getrennt durch zwei Leerzeichen. Bei Spielmodus-Info (Krieg) wechselt sie für 3 s und kehrt zurück.',mm:'<emerald> 1.240  <diamond> 35  <rose> 620'},
        {k:'Bossbar',v:'Farbe nach Inhalt: Tutorial gelb, Turm-Stand nach Team, Countdown blau. Text: Titel in Gold, Wert in Pergament.',mm:'Schritt 2/6 · Deck ansehen'},
        {k:'Tablist',v:'Kopf: Wortmarke als Glyph + Zeile „[Name] · {online} Spieler“. Fuß: Server + Discord in Holz-hell. Max. 2 Animationsframes pro Sekunde.',mm:'{network} · {online}/{max}'},
        {k:'Scoreboard',v:'Titel = Wortmarke-Glyph. Zeilen: Label in Holz-hell, Wert in Pergament, Trenner leer. Max. 9 Zeilen in der Lobby.',mm:'Rosen » 620'},
        {k:'Nametag',v:'Zeile 1: Rang-Prefix + Name in Rangfarbe, Clan-Tag in Grau dahinter. Zeile 2 (Untertitel): kursiv, Pergament-dunkel.',mm:'[Mod] Steve [ABC]'},
        {k:'MOTD',v:'Zeile 1: Wortmarke in Gold + Kurzslogan. Zeile 2: Aktuelles (Event, Update) in Pergament mit Rot als Akzent. Wartung: Zeile 2 in Orange.',mm:'<#ffcf4a>[Name] <#7a4a24>· <#f1e2c2>Das Kartenturnier beginnt'},
        {k:'Titel/Untertitel',v:'Nur für Meilensteine (Sieg, Truhe Boss, Level-Up). Titel Gold, Untertitel Pergament. Einblenden 10, Stehen 40, Ausblenden 10 Ticks.',mm:'SIEG! · +30 Rosen'}
      ],
      rarities: [
        {n:'Normal',v:'Locker gebunden. Grauer Rahmen.',b:'#b8c0cc',d:'#7a8494',tc:'#2a160a'},
        {n:'Besonders',v:'Blauer Faden. Rahmen mit 1 Glanzpunkt.',b:'#5ab4ff',d:'#2f7ad0',tc:'#2a160a'},
        {n:'Elite',v:'Runensiegel im Rahmen.',b:'#a05ae8',d:'#6a2ab0',tc:'#ffffff'},
        {n:'Titan',v:'Goldene Ketten an den Ecken.',b:'#ffcf4a',d:'#e0a420',tc:'#2a160a'},
        {n:'Boss',v:'Glühender Rahmen, 4-Frame-Animation.',b:'#e8403a',d:'#9a2018',tc:'#ffffff'}
      ],
      mineclash: [
        {k:'Kartentisch',v:'Typ „Tisch“. Holztisch mit roter Tischdecke (cloth), Sammlung links im Holzfach, Deck 4×2 mittig auf der Decke, Infozettel rechts. Kerze rechts unten bleibt als Lebenszeichen.'},
        {k:'Kampf-Panel',v:'Ersetzt die Hotbar: Holzleiste mit 4 großen Kartenplätzen + kleiner nächster Karte. Ausgewählt: 2 px Gold-Rahmen und 2 px nach oben. Zu teuer: 50 % Tinte darüber, Kosten in Rot.'},
        {k:'Energie',v:'XP-Leiste in Diamant-Blau (Resourcepack), Zahl darüber. 2× Energie: Leiste pulsiert, 3×: goldener Rand.'},
        {k:'Türme',v:'Steinsockel + Dach in Teamfarbe, Krone auf dem Haupt-Turm. HP-Leiste über dem Turm in Teamfarbe mit Tinten-Rahmen.'},
        {k:'Truppen-HP',v:'Kurze Leiste über dem Mob, Teamfarbe; unter 30 % HP wechselt sie zu Fehler-Rot.'},
        {k:'Truhen',v:'Gleiche Form, Beschläge in Rarity-Farbe. Bereit: goldenes Glimmen (Partikel) + Sound. Öffnen: Deckel springt, Karten fliegen in einem Bogen zum Spieler.'},
        {k:'Schatzhalle',v:'Podeste aus Holz mit Eisen, Statusschild als Pergamentzettel mit Countdown in Diamant-Blau.'},
        {k:'Shop',v:'Typ „Tisch“ mit Händlerin-Kopf oben links. Tagesangebote als 3 Kacheln, Preis-Pill darunter. Ausverkauft: Stempel „Weg!“ in Rot quer.'}
      ],
      modelSheet: [
        {k:'Maßeinheit',v:'1 Einheit = 1 Pixel = 1/16 Block. Texturen 1:1 (kein Mixed-Scale innerhalb eines Modells).'},
        {k:'Gesamthöhe',v:'32 px (2 Blöcke), wie Vanilla. Kinder/Lehrlinge 22 px.'},
        {k:'Kopf',v:'12×12×11 px (Vanilla 8×10×8). Etwa 37 % der Höhe. Kopf-Pivot am Hals.'},
        {k:'Körper',v:'Gerade Robe 9×12×6, endet an der Hüfte. Kragen 7×1.4×6.4, Knopfleiste 1.6 px breit, Gürtel 1.2 px mit Schnalle.'},
        {k:'Beine',v:'2× 3.6×7×3.6 in Hose, Schuhe 4×2×5. Keine Rock-Verbreiterung.'},
        {k:'Arme',v:'Verschränkt wie Vanilla, aber in Teilen: 2 Ärmel (3×3.4×5) + Unterarm-Balken (11.2×3.2×3.4) + Manschette + Hände (3.6×2.8×3.6).'},
        {k:'Nase',v:'3.4×6.2×3.4, eigenes Pivot an der Nasenwurzel. Wichtigstes Erkennungsmerkmal, darf beim Sprechen pendeln.'},
        {k:'Augen',v:'Weiß 3.2×2.4, Iris 1.7×2.1 (Grün Standard), Pupille 0.8×1.1. Iris nach innen versetzt (leichtes Schielen = freundlich).'},
        {k:'Lider',v:'Eigene Teile mit Pivot an der Oberkante, skaliert von 0.05 (offen) bis 1 (zu).'},
        {k:'Braue',v:'Durchgehende Braue 9.4×1.7, eigenes Pivot. Charakter über Dicke und Winkel.'},
        {k:'Mund',v:'Dunkle Mundplatte 6.4×1.6 unter der Nase, skaliert in y zum Sprechen.'},
        {k:'Textur',v:'Kräftige Flächen, 1 px dunklere Kante an allen Außenkanten, max. 3 Stufen pro Farbe, kaum Rauschen. Robe in Berufsfarbe, Details in Gold/Leder.'},
        {k:'Benennung',v:'Teile englisch und eindeutig: head_pivot, brow_pivot, lid_pivot_l, nose_pivot, mouth_pivot, robe, sleeve_l … (für Animationen und Code).'},
        {k:'Requisit',v:'Jeder Beruf hat ein großes Requisit, das die Silhouette verändert (Hut, Hammer, Helm, Kopftuch).'}
      ],
      anims: [
        {n:'Idle-Wippen',d:'Körper hebt sich leicht, nie ganz still.',t:'2 s · ±0.35 px'},
        {n:'Blinzeln',d:'Lider zu und wieder auf.',t:'alle 2.5–6 s · 3 Ticks'},
        {n:'Kopf folgt',d:'Kopf dreht zum nächsten Spieler im Radius 6, sonst langsames Umschauen.',t:'max. ±35°'},
        {n:'Braue hebt',d:'Bei Ansprechen, Überraschung, Angebot.',t:'0.9 s · +0.9 px'},
        {n:'Sprechen',d:'Mund skaliert, Nase pendelt synchron.',t:'9 Hz · solange Text läuft'},
        {n:'Begrüßen',d:'Arme lösen sich, eine Hand hebt sich kurz.',t:'1 s · beim Nähern'},
        {n:'Berufs-Geste',d:'Hämmern, Münzen zählen, Karten mischen, Schlüssel klimpern.',t:'alle 8–15 s'},
        {n:'Jubel',d:'Arme hoch, kleiner Hüpfer.',t:'bei Sieg, Kauf, Upgrade'},
        {n:'Schmollen',d:'Arme fester, Kopf zur Seite, Braue tief.',t:'bei Niederlage, Fehler'},
        {n:'Schlafen',d:'Kopf sinkt, Lider zu, Z-Partikel.',t:'nachts im Dorf'}
      ],
      cast: [
        {name:'Bertold Knubbel',role:'Bürgermeister',place:'Lobby, Dorfplatz',robe:'Weinrot, Goldsaum',prop:'Zylinderhut mit Goldband, Medaillon an Kette',trait:'Eitel, herzlich, übertreibt alles.',voice:'Feierlich, lange Sätze, Superlative.',face:'Braue oft oben, breites Lächeln',quote:'Ich habe diesen Brunnen persönlich eröffnet. Zweimal.'},
        {name:'Mira Feilsch',role:'Händlerin',place:'Shop',robe:'Smaragdgrün',prop:'Lila Kopftuch, Münzbeutel am Gürtel',trait:'Freundlich, geschäftstüchtig.',voice:'Schnell, Zahlen, Augenzwinkern.',face:'Ein Auge zugekniffen beim Angebot',quote:'Für dich? Sonderpreis. Also der normale.'},
        {name:'Gorm Faust',role:'Kampfmeister',place:'Arena-Eingang, Match-Queue',robe:'Eisengrau mit roter Schärpe',prop:'Holzschwert, Narbe über der Braue',trait:'Laut, ehrlich, feuert jeden an.',voice:'Kurze Ausrufe, viele Ausrufezeichen.',face:'Grinsen, Braue tief und dick',quote:'Ab in die Arena! Ich halte deine Rosen.'},
        {name:'Elsa Blatt',role:'Kartenmeisterin',place:'Kartentisch-Pavillon',robe:'Tintenblau',prop:'Runde Brille, Kartenfächer',trait:'Ruhig, präzise, belehrend.',voice:'Sachlich, Tipps zur Taktik.',face:'Brille rutscht, Braue einseitig',quote:'Ein Zombie allein ist traurig. Drei sind eine Strategie.'},
        {name:'Klunk',role:'Truhenwächter',place:'Schatzhalle',robe:'Braun mit Eisenbeschlägen',prop:'Riesiger Schlüsselbund',trait:'Wortkarg, liebt seine Truhen.',voice:'Ein bis drei Wörter.',face:'Halb geschlossene Lider',quote:'Noch acht Stunden. Sie schläft.'},
        {name:'Pitt Stollen',role:'Minenarbeiter',place:'Mine',robe:'Erdbraun',prop:'Gelber Helm mit Lampe, Rußfleck',trait:'Begeistert von jedem Stein.',voice:'Fröhlich, Bergmanns-Sprüche.',face:'Weit offene Augen',quote:'Heute glitzert’s! Oder ich hab Staub im Auge.'},
        {name:'Hedda Wall',role:'Clanwärterin',place:'Clanhalle',robe:'Dunkelrot',prop:'Clan-Register mit Siegel',trait:'Streng, fair, Regeln über alles.',voice:'Amtlich, Paragraphen-Ton.',face:'Braue gerade, Mund schmal',quote:'Ohne Siegel kein Clan. Ohne Clan kein Krieg.'},
        {name:'Prof. Funkel',role:'Forscher',place:'Forschungsturm (Kartenlevel)',robe:'Lila mit Brandflecken',prop:'Lupe, zerzauste Haarbüschel',trait:'Zerstreut, euphorisch.',voice:'Unterbricht sich selbst.',face:'Augen unterschiedlich weit offen',quote:'Faszinierend! Und leicht entflammbar.'},
        {name:'Ambo Hammerl',role:'Schmied',place:'Schmiede (Perks)',robe:'Grau, Lederschürze',prop:'Großer Hammer, dicke Braue',trait:'Kräftig, gutmütig.',voice:'Kurz, mit Lautmalerei („Klong.“).',face:'Dicke Braue, zufriedenes Nicken',quote:'Schnellere Schlösser? Klong. Erledigt.'},
        {name:'Knöpfchen',role:'Lobby-Erzähler',place:'Lobby, Tutorial, leere Zustände',robe:'Senfgelb, zu groß',prop:'Notizbuch und Feder',trait:'Kleiner Lehrling, neugierig, irrt oft.',voice:'Fragend, erklärt mit falscher Sicherheit.',face:'Große Augen, Braue hoch',quote:'Das Reich ist alt. Mindestens hundert. Glaube ich.'}
      ],
      mobRules: [
        {k:'Proportion',v:'Kopf um 30–50 % vergrößert gegenüber Vanilla, Körper leicht gestaucht, Gesamtgröße wie Vanilla.'},
        {k:'Gesicht',v:'Augen, Mund und ein Merkmal als eigene Teile, damit sie animiert reagieren (Angriff, Treffer, Tod).'},
        {k:'Merkmal',v:'Pro Mob ein übertriebenes Detail (Liste unten). Auf der Karte ist genau dieses Merkmal im Bild.'},
        {k:'Teamfarbe',v:'Ein kleines Stoffteil (Halstuch, Band, Fahne) in Teamfarbe, damit Freund und Feind in der Arena lesbar sind.'},
        {k:'Ausdruck',v:'Normal: grimmig-komisch, nie gruselig-realistisch. Boss: bedrohlich, aber mit Cartoon-Augen.'},
        {k:'Reaktionen',v:'Spawn: Plopp + Staubwolke. Treffer: weißes Aufblitzen 2 Ticks + Augen zu. Tod: Umfallen + Karte, die aufsteigt.'},
        {k:'Textur',v:'Wie Villager: Flächen, Kante 1 px dunkler, Palette aus Abschnitt 3 plus mob-eigene Grundfarbe.'}
      ],
      mobs: [
        {n:'Zombie',v:'Hängende Arme, ein Auge größer, zerrissenes Hemd.'},
        {n:'Skelettbogenschütze',v:'Großer Bogen, Kiefer klappert, Helm etwas zu groß.'},
        {n:'Höhlenspinne',v:'Viele Augen in verschiedenen Größen, Giftzahn-Tropfen.'},
        {n:'Snow Golem',v:'Kürbis mit breitem Grinsen, Zweigarme winken.'},
        {n:'Evoker',v:'Spitze Kapuze, Hände mit Runenglühen, sehr hohe Braue.'},
        {n:'Phantom',v:'Breite Flügel, große leuchtende Augen, Maul offen.'},
        {n:'Hexe',v:'Riesige Warzennase, Hut mit Knick, Trank am Gürtel.'},
        {n:'Blaze',v:'Kleiner Kopf, Stäbe als Flammenkrone, wütende Augenbrauen aus Feuer.'},
        {n:'Slime',v:'Wackelnde Würfelform, Augen schwimmen im Körper; Kinder kleiner und frecher.'},
        {n:'Enderman',v:'Lange Glieder, Augen als lila Schlitze, teleportiert mit Glitch-Pose.'},
        {n:'Iron Golem',v:'Riesige Fäuste, Blume auf der Schulter, Nase wie Villager.'},
        {n:'Enderdrache',v:'Großer Kopf mit Hörnern, Flügel mit Teamfarben-Band, Augen glühend.'},
        {n:'Wither',v:'Drei Köpfe mit drei verschiedenen Gesichtsausdrücken (wütend, gelangweilt, irre).'}
      ],
      nav: [
        {k:'Typ',v:'Landkarte auf Pergament, eingerahmt in Holz mit Eisenecken; Titelband „Karte des Reichs“.'},
        {k:'Stil',v:'Handgezeichnet, Tinten-Konturen, Wasser blassgrün-blau, Wald als runde Baumtupfen, Berge als Dreiecke mit Schnee. Basis: landkarte_entwurf.png.'},
        {k:'Orte',v:'Ankerplatz/Lobby (Mitte, Monument) · Kesseldorf (MineClash-Dorf) · Rosenarena (1v1) · Grenzlande (Krieg) · Nebelland (kommende Modi).'},
        {k:'Ortsmarke',v:'32×32 Emblem (Burg, Banner, Schwerter, Kompass, Krone) auf runder Pergament-Plakette mit Tinten-Kontur. Name darunter als Spruchband.'},
        {k:'Zustände',v:'Normal: Emblem ruhig. Hover: Emblem animiert + Tooltip mit Spielerzahl. Voll: Banner rot. Wartung: Emblem grau + Bauzaun-Icon.'},
        {k:'Villager',v:'Neben jeder Ortsmarke der zuständige Villager als Kopf-Item (Kampfmeister bei der Arena usw.).'},
        {k:'Tooltip',v:'Name in Gold, Zeile „{online} Spieler“, ein Satz Lore in Pergament-dunkel, „Klick: Reisen“ in Gold.'},
        {k:'Animation',v:'Pro Ort ein Emblem-Frame-Streifen (10 Frames, 3 Ticks). Wasser und Hintergrund statisch.'},
        {k:'Klickfläche',v:'Jeder Ort belegt 2×2 Zellen; Orte so platzieren, dass ihre Zellen nicht kollidieren.'}
      ],
      cosmetics: [
        {k:'Stil',v:'Gleiche Regeln: kräftige Flächen, 1 px Kante, Licht oben links. Keine Halbtransparenz außer bei Heiligenschein-Glow.'},
        {k:'Flügel',v:'Engel, Fee, Eis (frei) · Feuer, Dämon, Drache (Recht). Flügel schlagen langsam im Stand (3 s), schneller beim Sprinten.'},
        {k:'Kronen',v:'Bronze, Silber, Gold. Gleiche Form, Material nach Stufe; Gold mit roten Edelsteinen.'},
        {k:'Heiligenschein',v:'Weiß, Dunkel, Regenbogen. Schwebt 2 px über dem Kopf und dreht sich langsam.'},
        {k:'Rarität',v:'Cosmetics nutzen dieselben Rarity-Farben wie Karten, sichtbar im Auswahlmenü als Kachelfarbe.'},
        {k:'Vorschau',v:'Im Profilmenü auf einem drehenden Spielerkopf/Körper; Gesperrte mit Schloss-Badge und Hinweis, wie man sie bekommt.'}
      ],
      motion: [
        {k:'Grundsatz',v:'Bewegung zeigt Leben, nicht Ladezustand. Jedes Menü hat genau ein ruhiges Lebenszeichen (Kerze, Fackel, Feder).'},
        {k:'Lebenszeichen',v:'Kerze/Fackel 8 Frames · 3 Ticks. Immer am Rand, nie neben Text.'},
        {k:'Belohnung',v:'Edelstein an/aus, Wachssiegel prägt sich: 10–12 Frames · 2 Ticks, einmalig.'},
        {k:'Warnung',v:'Warnrune pulsiert 6 Frames · 4 Ticks, nur solange der Zustand besteht.'},
        {k:'Auswahl',v:'Kein Blinken. Ausgewählte Elemente bekommen Gold und bleiben ruhig.'},
        {k:'Web',v:'Übergänge 120–180 ms, Ease-out; Knopf drückt 2 px; Einblenden von Panels mit 6 px Hub. Respektiert „reduzierte Bewegung“.'},
        {k:'Sound Klick',v:'Holzklick (kurz, trocken) für alle Knöpfe.'},
        {k:'Sound Erfolg',v:'Glocke/Münzen bei Kauf und Belohnung; Fanfare nur bei Sieg und Boss-Truhe.'},
        {k:'Sound Fehler',v:'Dumpfer Holzschlag + Villager-„Hmm“.'}
      ],
      cssTokens: `:root {            /* Hellmodus: Pergament */
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
}`,
      web: [
        {k:'Konturen',v:'Karten und Knöpfe 3 px Tinte (border), Tabellen und Eingaben 1 px.'},
        {k:'Knöpfe',v:'shadcn Button-Varianten: default = primary mit Unterkante, secondary = Gold, outline = Pergament, destructive = Rot, ghost bleibt flach.'},
        {k:'Schrift',v:'Überschriften und Knöpfe Lilita One, alles andere Nunito, Logs/Konsole/Code JetBrains Mono.'},
        {k:'Seitenleiste',v:'Holz (#3b2617) in beiden Modi, aktiver Eintrag als Gold-Pill, Icons Lucide in Pergament.'},
        {k:'Tabellen',v:'Kopfzeile Tinte mit Gold-Text, Zeilen abwechselnd card/muted, Hover Gold 15 %.'},
        {k:'Status',v:'Online/Läuft = success, Startet = info, Pausiert = warning, Fehler/Gestoppt = destructive; immer als Pill mit Kontur.'},
        {k:'Diagramme',v:'chart-1 bis chart-5 in dieser Reihenfolge, Linien 2.5 px, keine Flächenverläufe.'},
        {k:'Login',v:'Pergament-Karte auf Holz, Wortmarke oben, Villager Knöpfchen als Illustration, Code-Eingabe als 6 einzelne Felder.'}
      ],
      laby: [
        {k:'HUD-Widgets',v:'Kleine Holz-Pills mit Tinten-Kontur, Icon + Zahl, Text Pergament. Abstand zum Bildschirmrand 4 px.'},
        {k:'AFK-Screen',v:'Abgedunkelte Welt, Pergament-Zettel mittig mit Knöpfchen-Kopf und „Du bist eingenickt.“ + Zeit.'},
        {k:'Admin-Panel',v:'Webpanel-Tokens im Dunkelmodus, kompakt (Abstände −25 %), Tabs als Gold-Pills.'},
        {k:'LSS',v:'Tokens als LSS-Variablen mit denselben Namen wie im Webpanel (--primary, --border …).'},
        {k:'Cosmetics',v:'Auswahl wie im Profilmenü: Kacheln in Rarity-Farbe.'}
      ],
      logo: [
        {k:'Wortmarke',v:'[Name] in Lilita-artiger, dicker Cartoon-Schrift; Gold-Füllung, 3 px Tinten-Kontur, 4 px Schlagschatten. Ingame als eigene Pixel-Glyph (max. 128×32).'},
        {k:'Bildmarke',v:'Ein Wappen/Schild (Form aus navigator/shield.png) mit dem Zentrum der Lore als Motiv. Wird erst gestaltet, wenn der Name feststeht.'},
        {k:'Varianten',v:'Voll (Bildmarke + Wortmarke), nur Wortmarke, nur Bildmarke (Favicon, Tab-Icon, Discord).'},
        {k:'Größen',v:'Ingame 16, 32, 64 px Höhe; Web min. 24 px Höhe.'},
        {k:'Schutzraum',v:'Mindestens eine Konturbreite × 4 rundum.'},
        {k:'Hintergründe',v:'Auf Holz, Pergament und Band-Rot; nie auf Fotos ohne Holzplakette dahinter.'},
        {k:'Slogan',v:'Kurz, im Ton der Villager. Vorschläge folgen mit dem Namen.'}
      ],
      dos: ['Nur Palette-Farben verwenden','Jede Fläche mit Tinten-Kontur','Licht immer von oben links','Leere Zustände mit Villager-Spruch','Gefahr-Aktionen immer mit Rückfrage','Klickflächen exakt auf 18-px-Zellen','Neue Elemente zuerst hier eintragen'],
      donts: ['Kein Lila Primary mehr, kein „modern dunkel“','Keine weichen Verläufe oder Weichzeichner-Schatten','Keine Schrift außer Minecraft-Font ingame','Kein dauerhaftes Blinken','„Helix“ nie spielersichtbar','„Episch“ nicht verwenden, nur „Elite“','Keine realistisch-gruseligen Mob-Gesichter'],
      open: ['Name des Netzwerks und des Reichs (ersetzt [Name])','Zentrum der Lore (ersetzt Arbeitstitel „Ankerstein“) und damit die Bildmarke','Spieler-Ränge (Premium/VIP) neben den Team-Rängen','Ob Villager-Rede im Chat erscheint oder nur als Hologramm über dem Villager','Eigene Ingame-Schrift für Titel (Version 2)','TTS-Werkzeug und Stimmen für Stufe 2 auswählen']
    };
  }
}
