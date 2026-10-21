# Rang-Abzeichen (DESIGN §9 „AC3“)

`mix.js` zeichnet die Rang-Abzeichen-Varianten AC1–AC3 als 9 px hohe Pixel-Glyphen (Node + `pngjs`).
Gewählt ist **AC3**; die fertigen Glyphen liegen in `helix-addon-guis-paper/src/pack/resources/ranks/<rang>.png`
(Font `helix_guis:ranks`, Codepoints in helix-api `RankBadges`) und als Vorlage in `docs/design/brand/ranks/`.

Neu zeichnen: `NODE_PATH=<pfad-zu-node_modules-mit-pngjs> node mix.js`, danach die AC3-Glyphen nach
`helix-addon-guis-paper/src/pack/resources/ranks/` kopieren und `./gradlew :helix-addon-guis-paper:generatePack` laufen lassen.
Die Mock-up-Tafeln brauchen die Minecraft-Schrift (`ascii.png`), die nicht im Repo liegt.
