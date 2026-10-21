// KronlandMC – Rang-Prefix-Entwürfe A/B/C als Font-Glyphen (9 px hoch) + Mock-up-Tafeln.
// node rang.js   (NODE_PATH mit pngjs)
'use strict';
const fs = require('fs');
const path = require('path');
const { PNG } = require('pngjs');

const OUT = __dirname;
const hex = (h) => [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16), 255];
const mix = (a, b, t) => [0, 1, 2].map((i) => Math.round(a[i] + (b[i] - a[i]) * t)).concat([255]);
const light = (c) => mix(c, [255, 255, 255], 0.32);
const dark = (c) => mix(c, [0, 0, 0], 0.38);
const lum = (c) => (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255;

const INK = hex('2a160a');
const GOLD = { l: hex('fff0a8'), b: hex('ffcf4a'), d: hex('e0a420') };
const PARCH = { l: hex('fbf3dc'), b: hex('f1e2c2'), d: hex('d8c49c') };
const WOOD_L = hex('a8743c');

const RANKS = [
  { key: 'owner', label: 'OWNER', name: 'Owner', col: hex('d8382e'), icon: 'crown' },
  { key: 'manager', label: 'MANAGER', name: 'Manager', col: hex('ff6a8a'), icon: 'gem' },
  { key: 'developer', label: 'DEVELOPER', name: 'Developer', col: hex('a05ae8'), icon: 'gear' },
  { key: 'admin', label: 'ADMIN', name: 'Admin', col: hex('ff5a4a'), icon: 'hammer' },
  { key: 'mod', label: 'MOD', name: 'Mod', col: hex('ffb43a'), icon: 'sword' },
  { key: 'support', label: 'SUPPORT', name: 'Support', col: hex('4ac85e'), icon: 'heart' },
  { key: 'tester', label: 'TESTER', name: 'Tester', col: hex('5ab4ff'), icon: 'bug' },
  { key: 'beta', label: 'BETA', name: 'Beta', col: hex('ffcf4a'), icon: 'star' },
];

// ---------- Raster ----------------------------------------------------------
class Img {
  constructor(w, h, fill) {
    this.w = w; this.h = h; this.d = new Uint8ClampedArray(w * h * 4);
    if (fill) for (let i = 0; i < w * h; i++) this.d.set(fill, i * 4);
  }
  get(x, y) { const i = (y * this.w + x) * 4; return [this.d[i], this.d[i + 1], this.d[i + 2], this.d[i + 3]]; }
  set(x, y, c) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h || !c) return;
    const i = (y * this.w + x) * 4, a = c[3] / 255;
    if (a >= 1) { this.d.set(c, i); return; }
    const ia = 1 - a, da = this.d[i + 3] / 255, oa = a + da * ia;
    for (let k = 0; k < 3; k++) this.d[i + k] = oa ? (c[k] * a + this.d[i + k] * da * ia) / oa : 0;
    this.d[i + 3] = oa * 255;
  }
  rect(x, y, w, h, c) { for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) this.set(i, j, c); }
  blit(src, x, y, s = 1) {
    for (let j = 0; j < src.h * s; j++) for (let i = 0; i < src.w * s; i++) {
      const c = src.get(Math.floor(i / s), Math.floor(j / s));
      if (c[3]) this.set(x + i, y + j, c);
    }
  }
  save(p) {
    const png = new PNG({ width: this.w, height: this.h });
    png.data = Buffer.from(this.d);
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, PNG.sync.write(png));
  }
  static load(p) {
    const png = PNG.sync.read(fs.readFileSync(p));
    const im = new Img(png.width, png.height);
    im.d.set(png.data);
    return im;
  }
}

// ---------- Mini-Pixelschrift (5 px Großbuchstaben) --------------------------
const MINI = {
  A: ['.#.', '#.#', '###', '#.#', '#.#'], B: ['##.', '#.#', '##.', '#.#', '##.'], D: ['##.', '#.#', '#.#', '#.#', '##.'],
  E: ['###', '#..', '##.', '#..', '###'], G: ['.##', '#..', '#.#', '#.#', '.##'], I: ['###', '.#.', '.#.', '.#.', '###'],
  L: ['#..', '#..', '#..', '#..', '###'], M: ['#...#', '##.##', '#.#.#', '#...#', '#...#'], N: ['#..#', '##.#', '#.##', '#..#', '#..#'],
  O: ['.##.', '#..#', '#..#', '#..#', '.##.'], P: ['##.', '#.#', '##.', '#..', '#..'], R: ['##.', '#.#', '##.', '#.#', '#.#'],
  S: ['.##', '#..', '.#.', '..#', '##.'], T: ['###', '.#.', '.#.', '.#.', '.#.'], U: ['#.#', '#.#', '#.#', '#.#', '###'],
  V: ['#.#', '#.#', '#.#', '#.#', '.#.'], W: ['#...#', '#...#', '#.#.#', '##.##', '#...#'],
};
const miniWidth = (s) => [...s].reduce((w, ch, i) => w + MINI[ch][0].length + (i ? 1 : 0), 0);
function miniText(img, s, x, y, col, shadow) {
  let cx = x;
  for (const ch of s) {
    const g = MINI[ch];
    for (let r = 0; r < 5; r++) for (let c = 0; c < g[r].length; c++) if (g[r][c] === '#') {
      if (shadow) img.set(cx + c, y + r + 1, shadow);
      img.set(cx + c, y + r, col);
    }
    cx += g[0].length + 1;
  }
}

// ---------- Glyphen ---------------------------------------------------------
const H = 9;
function textColors(col) {
  return lum(col) > 0.55 ? { t: INK, s: null } : { t: PARCH.l, s: dark(col) };
}

// A „Plakette“: abgerundete Pille, helle Oberkante, dunkle Unterkante, Großbuchstaben.
function glyphA(r) {
  const tw = miniWidth(r.label), w = tw + 8;
  const im = new Img(w, H);
  for (let y = 0; y < H; y++) for (let x = 0; x < w; x++) {
    const corner = (x === 0 || x === w - 1) && (y === 0 || y === H - 1);
    if (corner) continue;
    const edge = y === 0 || y === H - 1 || x === 0 || x === w - 1;
    im.set(x, y, edge ? INK : y === 1 ? light(r.col) : y === H - 2 ? dark(r.col) : r.col);
  }
  // innere Eckpixel weich
  [[1, 1], [w - 2, 1], [1, H - 2], [w - 2, H - 2]].forEach(([x, y]) => im.set(x, y, dark(r.col)));
  im.set(1, 1, light(r.col));
  const { t } = textColors(r.col);
  miniText(im, r.label, 4, 2, t, null);
  return im;
}

// B „Wappen“: 9×9-Schild mit Symbol (Name folgt als normaler Text in Rangfarbe).
const SHIELD = ['#########', '#########', '#########', '#########', '#########', '.#######.', '..#####..', '...###...', '....#....'];
const ICONS = {
  crown: ['#.#.#', '#####', '#####', '.....', '.....'],
  gem: ['.###.', '#####', '.###.', '..#..', '.....'],
  gear: ['.#.#.', '#####', '##.##', '#####', '.#.#.'],
  hammer: ['####.', '####.', '.#...', '.#...', '.#...'],
  sword: ['....#', '...#.', '#.#..', '.#...', '#.#..'],
  heart: ['##.##', '#####', '#####', '.###.', '..#..'],
  bug: ['#.#.#', '.###.', '#####', '.###.', '#.#.#'],
  star: ['..#..', '#####', '.###.', '.#.#.', '#...#'],
};
function glyphB(r) {
  const im = new Img(9, 9);
  const inMask = (x, y) => x >= 0 && y >= 0 && x < 9 && y < 9 && SHIELD[y][x] === '#';
  for (let y = 0; y < 9; y++) for (let x = 0; x < 9; x++) {
    if (!inMask(x, y)) continue;
    const border = !inMask(x - 1, y) || !inMask(x + 1, y) || !inMask(x, y - 1) || !inMask(x, y + 1);
    im.set(x, y, border ? INK : y === 1 ? light(r.col) : y >= 5 ? dark(r.col) : r.col);
  }
  const sym = lum(r.col) > 0.6 ? INK : PARCH.l;
  const ic = ICONS[r.icon];
  for (let y = 0; y < 5; y++) for (let x = 0; x < 5; x++) if (ic[y][x] === '#') im.set(2 + x, 1 + y, sym);
  return im;
}

// C „Spruchband“: Schwalbenschwanz-Band in Rangfarbe, Goldsaum oben/unten, Text in Pergament.
function glyphC(r) {
  const tw = miniWidth(r.label), w = tw + 14;
  const im = new Img(w, H);
  const notch = (y) => Math.max(0, 2 - Math.abs(4 - y)); // Kerbe in der Mitte (Schwalbenschwanz)
  const inMask = (x, y) => y >= 0 && y < H && x >= notch(y) && x <= w - 1 - notch(y);
  for (let y = 0; y < H; y++) for (let x = 0; x < w; x++) {
    if (!inMask(x, y)) continue;
    const side = !inMask(x - 1, y) || !inMask(x + 1, y);
    let c;
    if (y === 0) c = GOLD.l;
    else if (y === H - 1) c = GOLD.d;
    else if (side) c = INK;
    else if (x < 4 || x > w - 5) c = dark(r.col); // Bandenden etwas dunkler (gefaltet)
    else c = y === 1 ? light(r.col) : r.col;
    im.set(x, y, c);
  }
  const t = lum(r.col) > 0.6 ? INK : PARCH.l;
  miniText(im, r.label, 7, 2, t, null);
  return im;
}


// ---------- Misch-Varianten A+C ---------------------------------------------
// AC1: Pille wie A, Goldsaum oben/unten (C), Tinten-Kontur außen.
function glyphAC1(r) {
  const tw = miniWidth(r.label), w = tw + 8;
  const im = new Img(w, H);
  for (let y = 0; y < H; y++) for (let x = 0; x < w; x++) {
    const corner = (x === 0 || x === w - 1) && (y === 0 || y === H - 1);
    if (corner) continue;
    let c;
    if (y === 0 || y === H - 1 || x === 0 || x === w - 1) c = INK;
    else if (y === 1) c = GOLD.l;
    else if (y === H - 2) c = GOLD.d;
    else if (x === 1) c = light(r.col);
    else if (x === w - 2) c = dark(r.col);
    else c = r.col;
    im.set(x, y, c);
  }
  im.set(1, 1, GOLD.b); im.set(w - 2, 1, GOLD.b); im.set(1, H - 2, GOLD.d); im.set(w - 2, H - 2, GOLD.d);
  const { t } = textColors(r.col);
  miniText(im, r.label, 4, 2, t, null);
  return im;
}
// AC2: Band wie C, aber kompakt: nur kleine Kerben an den Enden, Goldsaum, Text wie A.
function glyphAC2(r) {
  const tw = miniWidth(r.label), w = tw + 10;
  const im = new Img(w, H);
  const notch = (y) => (y === 4 ? 2 : (y === 3 || y === 5) ? 1 : 0);
  const inMask = (x, y) => y >= 0 && y < H && x >= notch(y) && x <= w - 1 - notch(y);
  for (let y = 0; y < H; y++) for (let x = 0; x < w; x++) {
    if (!inMask(x, y)) continue;
    const side = !inMask(x - 1, y) || !inMask(x + 1, y);
    let c;
    if (y === 0) c = GOLD.l;
    else if (y === H - 1) c = GOLD.d;
    else if (side) c = INK;
    else if (x < 3 || x > w - 4) c = dark(r.col);
    else c = y === 1 ? light(r.col) : r.col;
    im.set(x, y, c);
  }
  const { t } = textColors(r.col);
  miniText(im, r.label, 5, 2, t, null);
  return im;
}
// AC3: Pille mit goldenem Rahmen rundum, innen Rangfarbe mit Licht oben links; Owner mit kleiner Krone.
const CROWN = ['#.#.#', '#####', '#####', '.###.', '#####'];
function glyphAC3(r) {
  const crown = r.key === 'owner';
  const tw = miniWidth(r.label) + (crown ? 7 : 0), w = tw + 8;
  const im = new Img(w, H);
  const bright = lum(r.col) > 0.55;
  for (let y = 0; y < H; y++) for (let x = 0; x < w; x++) {
    const corner = (x === 0 || x === w - 1) && (y === 0 || y === H - 1);
    if (corner) continue;
    let c;
    if (y === 0) c = GOLD.l;
    else if (y === H - 1) c = bright ? INK : GOLD.d;
    else if (x === 0) c = bright ? GOLD.d : GOLD.l;
    else if (x === w - 1) c = bright ? INK : GOLD.d;
    else if (y === 1 || x === 1) c = light(r.col);
    else if (y === H - 2 || x === w - 2) c = dark(r.col);
    else c = r.col;
    im.set(x, y, c);
  }
  // Ecken weich in Gold
  [[1, 0], [0, 1]].forEach(([x, y]) => im.set(x, y, GOLD.l));
  const { t } = textColors(r.col);
  let tx = 4;
  if (crown) {
    for (let j = 0; j < 5; j++) for (let i = 0; i < 5; i++) if (CROWN[j][i] === '#') im.set(tx + i, 2 + j, j === 0 ? GOLD.l : GOLD.b);
    im.set(tx + 2, 4, hex('5ab4ff'));
    tx += 7;
  }
  miniText(im, r.label, tx, 2, t, null);
  return im;
}

// ---------- Minecraft-Schrift (ascii.png) ------------------------------------
const FONT = Img.load(path.join(OUT, 'mcfont/ascii.png'));
const CHARS = JSON.parse(fs.readFileSync(path.join(OUT, 'mcfont/ascii_chars.json'), 'utf8'));
const MAP = new Map();
CHARS.forEach((row, ry) => [...row].forEach((ch, cx) => {
  if (ch === '\u0000') return;
  let wmax = -1;
  for (let x = 0; x < 8; x++) for (let y = 0; y < 8; y++) if (FONT.get(cx * 8 + x, ry * 8 + y)[3] > 0) wmax = Math.max(wmax, x);
  MAP.set(ch, { cx, ry, w: wmax + 1 });
}));
function mcWidth(s) { let w = 0; for (const ch of s) w += ch === ' ' ? 4 : (MAP.get(ch) ? MAP.get(ch).w + 1 : 6); return w; }
function mcText(img, s, x, y, col) { // y = Oberkante der 8er-Zelle; Schatten wie Minecraft (¼ Helligkeit, +1/+1)
  const sh = col.slice(0, 3).map((v) => Math.floor(v / 4)).concat([255]);
  let cx = x;
  for (const ch of s) {
    if (ch === ' ') { cx += 4; continue; }
    const g = MAP.get(ch);
    if (!g) { cx += 6; continue; }
    for (let j = 0; j < 8; j++) for (let i = 0; i < g.w; i++) if (FONT.get(g.cx * 8 + i, g.ry * 8 + j)[3] > 0) {
      img.set(cx + i + 1, y + j + 1, sh);
      img.set(cx + i, y + j, col);
    }
    cx += g.w + 1;
  }
  return cx;
}

// ---------- Mock-ups --------------------------------------------------------
let BG;
try { BG = Img.load('/workspace/.bbshots/schriftzug/bg_B.png'); } catch (e) { BG = null; }
function backdrop(w, h, ox, oy) {
  const im = new Img(w, h, hex('2b3a5a'));
  if (BG) for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const sx = Math.min(BG.w - 1, Math.floor((ox + x * 3) % BG.w)), sy = Math.min(BG.h - 1, Math.floor(oy + y * 3));
    im.set(x, y, BG.get(sx, sy));
  }
  return im;
}

const PEOPLE = [
  ['owner', 'Tytoss', 'Willkommen in Kronstadt!'],
  ['developer', 'Pixelbaron', 'Fix ist live, bitte testen.'],
  ['mod', 'Ritter_Kurt', 'Bitte keine Werbung im Chat.'],
  ['support', 'Hilda', 'Schreib /bug, dann schaue ich es mir an.'],
  ['tester', 'Brotkruste', 'Auktionshaus geht wieder :)'],
  ['beta', 'Knappe42', 'Danke fuers Testen!'],
  [null, 'Steve', 'Hallo zusammen'],
];

// Inhalt einer Zeile: Abzeichen (Glyph) + Name + » + Text; für B zusätzlich der Rangname als Text.
function lineParts(style, key) {
  const r = RANKS.find((x) => x.key === key);
  return r;
}
function drawLine(img, style, key, name, msg, x, y, withMsg = true) {
  const r = key ? RANKS.find((k) => k.key === key) : null;
  let cx = x;
  if (r) {
    const g = GLYPHS[style][r.key];
    img.blit(g, cx, y); // Glyph 9 px, Oberkante = Zeilenoberkante (ascent 8 → bündig mit Text)
    cx += g.w + 1;
    if (style === 'B') cx = mcText(img, r.name, cx + 1, y + 1, r.col) + 3;
    else cx += 3;
  }
  const nameCol = r ? (r.key === 'beta' ? hex('ffe48a') : mix(r.col, [255, 255, 255], 0.15)) : hex('c8bca8');
  cx = mcText(img, name, cx, y + 1, nameCol);
  if (withMsg) {
    cx = mcText(img, ' » ', cx, y + 1, WOOD_L);
    mcText(img, msg, cx, y + 1, PARCH.b);
  }
  return cx;
}

function chatMock(style) { // 1× Minecraft-Pixel: Chatbreite 320, Zeile 9 px
  const W = 330, lines = PEOPLE.length, H2 = lines * 9 + 6;
  const im = backdrop(W, H2 + 8, 200, 40);
  im.rect(2, 4, 320, lines * 9 + 2, [0, 0, 0, 128]);
  PEOPLE.forEach(([k, n, m], i) => drawLine(im, style, k, n, m, 4, 5 + i * 9));
  return im;
}
function tabMock(style) {
  const W = 200, rows = PEOPLE.length;
  const im = backdrop(W, rows * 9 + 12, 600, 20);
  im.rect(10, 4, 180, rows * 9 + 4, [0, 0, 0, 110]);
  PEOPLE.forEach(([k, n], i) => {
    const y = 6 + i * 9;
    im.rect(12, y, 178 - 4, 8, [255, 255, 255, 30]);
    im.rect(12, y, 8, 8, hex('b07850')); im.rect(13, y + 3, 2, 1, INK); im.rect(17, y + 3, 2, 1, INK); // Kopf
    drawLine(im, style, k, n, '', 22, y - 1, false);
  });
  return im;
}
function nametagMock(style) {
  const items = [['owner', 'Tytoss'], ['mod', 'Ritter_Kurt'], ['tester', 'Brotkruste'], ['beta', 'Knappe42']];
  const W = 420, im = backdrop(W, 60, 900, 120);
  let x = 6;
  items.forEach(([k, n]) => {
    const tmp = new Img(200, 12);
    const end = drawLine(tmp, style, k, n, '', 2, 1, false);
    const tw = end + 1;
    im.rect(x, 8, tw + 2, 11, [0, 0, 0, 64]);
    im.blit(cropImg(tmp, 0, 0, tw + 2, 11), x, 8);
    // Kopf/Körper-Silhouette
    const cx = x + Math.floor(tw / 2) - 4;
    im.rect(cx, 24, 8, 8, hex('b07850')); im.rect(cx + 1, 27, 2, 1, INK); im.rect(cx + 5, 27, 2, 1, INK);
    im.rect(cx, 32, 8, 12, hex('3a6ea5'));
    x += tw + 16;
  });
  return im;
}
function cropImg(src, x, y, w, h) { const o = new Img(w, h); for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) if (x + i < src.w && y + j < src.h) o.set(i, j, src.get(x + i, y + j)); return o; }

// ---------- Tafeln -----------------------------------------------------------
const TITLES = {
  AC1: 'AC1  Pille mit Goldsaum oben/unten, Tinten-Kontur',
  AC2: 'AC2  Kompaktes Band mit kleinen Kerben und Goldsaum',
  AC3: 'AC3  Pille mit Goldrahmen rundum (Owner mit Krone)',
};
const GLYPHS = { A: {}, C: {}, AC1: {}, AC2: {}, AC3: {} };
for (const r of RANKS) { GLYPHS.A[r.key] = glyphA(r); GLYPHS.C[r.key] = glyphC(r); GLYPHS.AC1[r.key] = glyphAC1(r); GLYPHS.AC2[r.key] = glyphAC2(r); GLYPHS.AC3[r.key] = glyphAC3(r); }

function label(img, s, x, y, scale, col) { // Überschrift in Minecraft-Schrift, skaliert
  const tmp = new Img(mcWidth(s) + 2, 10);
  mcText(tmp, s, 0, 0, col);
  img.blit(tmp, x, y, scale);
}

function tafel(style) {
  const W = 1920;
  const chat = chatMock(style), tab = tabMock(style), tag = nametagMock(style);
  const H2 = 70 + 9 * 6 + 24 + 24 + chat.h * 3 + 30 + 24 + Math.max(tab.h, tag.h) * 3 + 24;
  const im = new Img(W, H2, hex('1b1410'));
  label(im, TITLES[style], 24, 20, 3, GOLD.b);
  // Glyph-Leiste 6×
  let x = 24, y = 70;
  const sum = RANKS.reduce((a, r) => a + GLYPHS[style][r.key].w, 0);
  const sc = Math.min(6, Math.floor((W - 48 - 24 * (RANKS.length - 1)) / sum));
  for (const r of RANKS) { const g = GLYPHS[style][r.key]; im.blit(g, x, y + Math.floor((6 - sc) * 9 / 2), sc); x += g.w * sc + 24; }
  y += 9 * 6 + 24;
  label(im, 'Chat (GUI-Scale 3)', 24, y, 2, PARCH.d); y += 24;
  im.blit(chat, 24, y, 3);
  const yy = y;
  label(im, 'Chat (GUI-Scale 1 - Originalgroesse)', 1060, yy - 24, 2, PARCH.d);
  im.blit(chat, 1060, yy);
  label(im, 'Chat (GUI-Scale 2)', 1060, yy + chat.h + 16, 2, PARCH.d);
  im.blit(cropImg(chat, 0, 0, 330, chat.h), 1060, yy + chat.h + 40, 2);
  y += chat.h * 3 + 30;
  label(im, 'Tablist (GUI-Scale 3)', 24, y, 2, PARCH.d);
  label(im, 'Nametag ueber dem Kopf (3x)', 24 + tab.w * 3 + 40, y, 2, PARCH.d);
  y += 24;
  im.blit(tab, 24, y, 3);
  im.blit(tag, 24 + tab.w * 3 + 40, y, 3);
  return im;
}

const MIX = path.join(OUT, 'mix');
const STYLES = ['AC1', 'AC2', 'AC3'];
const sheets = {};
for (const st of STYLES) {
  for (const r of RANKS) GLYPHS[st][r.key].save(path.join(MIX, st, `glyph_${r.key}.png`));
  sheets[st] = tafel(st);
  sheets[st].save(path.join(MIX, st, 'tafel.png'));
}
// Vergleich: oben A und C klein (Glyph-Leisten 4×), darunter die drei Mischungen
const refH = 70 + 2 * (9 * 4 + 50);
const total = refH + Object.values(sheets).reduce((h, t) => h + t.h, 0) + 70;
const cmp = new Img(1920, total, hex('120d0a'));
label(cmp, 'KronlandMC - Rang-Prefixe: Mischung aus A und C', 24, 16, 3, GOLD.l);
let oy = 70;
for (const [st, name] of [['A', 'Zum Vergleich: A Plakette'], ['C', 'Zum Vergleich: C Spruchband']]) {
  label(cmp, name, 24, oy, 2, PARCH.d);
  let x = 24;
  for (const r of RANKS) { const g = GLYPHS[st][r.key]; cmp.blit(g, x, oy + 22, 4); x += g.w * 4 + 16; }
  oy += 9 * 4 + 50;
}
cmp.rect(0, oy, 1920, 2, hex('5a3a22')); oy += 6;
for (const st of STYLES) { cmp.blit(sheets[st], 0, oy); oy += sheets[st].h; cmp.rect(0, oy - 2, 1920, 2, hex('5a3a22')); }
cmp.save(path.join(MIX, 'vergleich_mix.png'));
const dims = {};
for (const st of STYLES) dims[st] = Object.fromEntries(RANKS.map((r) => [r.key, `${GLYPHS[st][r.key].w}x${GLYPHS[st][r.key].h}`]));
fs.writeFileSync(path.join(MIX, 'glyph-masse.json'), JSON.stringify(dims, null, 2));
console.log(JSON.stringify({ vergleich: [cmp.w, cmp.h], dims }));
