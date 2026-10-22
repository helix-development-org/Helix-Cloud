// KronlandMC-Bildmarke: drei Entwürfe als Pixel-Art im Kronland-Stil (DESIGN §3/§4/§19).
// Rein rechnend (RGBA-Puffer), läuft in Node (Vorschau) und in Blockbench (Texturen + Export).
(function (root) {
  const T = {
    ink: [0x2a, 0x16, 0x0a],
    wood: { l: [0x9a, 0x62, 0x34], b: [0x7a, 0x4a, 0x24], d: [0x5a, 0x34, 0x18] },
    woodD: { l: [0x5a, 0x34, 0x18], b: [0x45, 0x2a, 0x14], d: [0x3b, 0x26, 0x17] },
    par: { l: [0xff, 0xfa, 0xf0], b: [0xf1, 0xe2, 0xc2], d: [0xd8, 0xc0, 0x8e] },
    iron: { l: [0xd6, 0xda, 0xe0], b: [0xa8, 0xad, 0xb6], d: [0x6e, 0x73, 0x7c] },
    stone: { l: [0xc8, 0xc2, 0xb6], b: [0x9e, 0x98, 0x8c], d: [0x6e, 0x68, 0x5e] },
    gold: { l: [0xff, 0xf0, 0xa8], b: [0xff, 0xcf, 0x4a], d: [0xe0, 0xa4, 0x20] },
    rib: { l: [0xff, 0x7a, 0x6a], b: [0xd8, 0x38, 0x2e], d: [0x9a, 0x20, 0x18] },
    em: { l: [0x8c, 0xf0, 0x9a], b: [0x4a, 0xc8, 0x5e], d: [0x2a, 0x8a, 0x3e] },
    dia: { l: [0xa8, 0xdc, 0xff], b: [0x5a, 0xb4, 0xff], d: [0x2f, 0x7a, 0xd0] },
    night: { l: [0x3a, 0x22, 0x10], b: [0x2a, 0x16, 0x0a], d: [0x1a, 0x0c, 0x04] },
    inkM: { l: [0x2a, 0x16, 0x0a], b: [0x2a, 0x16, 0x0a], d: [0x2a, 0x16, 0x0a] },
  };

  // ---- Masken im 64er-Entwurfsraum -------------------------------------------------------------
  const circle = (cx, cy, r) => (u, v) => (u - cx) ** 2 + (v - cy) ** 2 <= r * r;
  const ellipse = (cx, cy, rx, ry) => (u, v) => ((u - cx) / rx) ** 2 + ((v - cy) / ry) ** 2 <= 1;
  const rect = (x0, y0, x1, y1) => (u, v) => u >= x0 && u < x1 && v >= y0 && v < y1;
  const rrect = (x0, y0, x1, y1, r) => (u, v) => {
    if (u < x0 || u >= x1 || v < y0 || v >= y1) return false;
    const cx = Math.min(Math.max(u, x0 + r), x1 - r), cy = Math.min(Math.max(v, y0 + r), y1 - r);
    return (u - cx) ** 2 + (v - cy) ** 2 <= r * r;
  };
  const poly = (pts) => (u, v) => {
    let inside = false;
    for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
      const [xi, yi] = pts[i], [xj, yj] = pts[j];
      if ((yi > v) !== (yj > v) && u < ((xj - xi) * (v - yi)) / (yj - yi) + xi) inside = !inside;
    }
    return inside;
  };
  const or = (...m) => (u, v) => m.some((f) => f(u, v));
  const and = (...m) => (u, v) => m.every((f) => f(u, v));
  const not = (m) => (u, v) => !m(u, v);
  const ring = (cx, cy, r0, r1) => and(circle(cx, cy, r1), not(circle(cx, cy, r0)));

  const FONT = {
    M: ['X...X', 'XX.XX', 'X.X.X', 'X...X', 'X...X'],
    C: ['.XXX', 'X...', 'X...', 'X...', '.XXX'],
  };
  // Text als Maske: Buchstaben-Pixel der Größe s, links oben bei (x, y).
  function text(str, x, y, s, gap = 1) {
    const cells = [];
    let cx = x;
    for (const ch of str) {
      const g = FONT[ch];
      g.forEach((row, r) => [...row].forEach((c, k) => { if (c === 'X') cells.push([cx + k * s, y + r * s]); }));
      cx += (g[0].length + gap) * s;
    }
    return (u, v) => cells.some(([a, b]) => u >= a && u < a + s && v >= b && v < b + s);
  }
  const textWidth = (str, s, gap = 1) => [...str].reduce((w, ch) => w + (FONT[ch][0].length + gap) * s, -gap * s);

  // Heater-Schild als Polygon (Spitze unten), Kurve per quadratischer Bézier.
  function shield(x0, y0, x1, y1) {
    const pts = [[x0, y0], [x1, y0], [x1, y0 + (y1 - y0) * 0.42]];
    const mx = (x0 + x1) / 2;
    const q = (a, b, c, t) => (1 - t) ** 2 * a + 2 * (1 - t) * t * b + t * t * c;
    for (let t = 0.1; t <= 1.0001; t += 0.1) pts.push([q(x1, x1, mx, t), q(y0 + (y1 - y0) * 0.42, y1 - (y1 - y0) * 0.12, y1, t)]);
    for (let t = 0.1; t < 1; t += 0.1) pts.push([q(mx, x0, x0, t), q(y1, y1 - (y1 - y0) * 0.12, y0 + (y1 - y0) * 0.42, t)]);
    pts.push([x0, y0 + (y1 - y0) * 0.42]);
    return poly(pts);
  }

  // Zackenkrone: Basis (x0..x1, Band yb..yb+bh), fünf Zacken mit Spitzen-Höhen.
  function crownMasks(x0, x1, yTop, yb, bh) {
    const w = x1 - x0, step = w / 4;
    const tips = [0, 1, 2, 3, 4].map((i) => [x0 + i * step, i === 2 ? yTop : (i % 2 ? yTop + (yb - yTop) * 0.42 : yTop + (yb - yTop) * 0.22)]);
    const pts = [[x0, yb + bh], [x0, tips[0][1]]];
    for (let i = 0; i < 4; i++) {
      pts.push([tips[i][0] + step * 0.18, tips[i][1] + step * 0.2]);
      pts.push([tips[i][0] + step * 0.5, yb - (yb - yTop) * 0.12]);
      pts.push([tips[i + 1][0] - step * 0.18, tips[i + 1][1] + step * 0.2]);
      pts.push([tips[i + 1][0], tips[i + 1][1]]);
    }
    pts.push([x1, yb + bh]);
    return { body: poly(pts), band: rect(x0, yb, x1, yb + bh), tips, step };
  }

  // Kleine Krone mit drei Zacken (für 16/32 px).
  function crown3(x0, x1, yTop, yb, bh) {
    const m = (x0 + x1) / 2, w = x1 - x0, side = yTop + (yb - yTop) * 0.3, valley = yb - (yb - yTop) * 0.35;
    return poly([[x0, yb + bh], [x0, side], [x0 + w * 0.27, valley], [m, yTop], [x1 - w * 0.27, valley], [x1, side], [x1, yb + bh]]);
  }

  // ---- Rasterizer ---------------------------------------------------------------------------------
  function render(N, draw) {
    const px = new Uint8ClampedArray(N * N * 4);
    const k = N / 64;
    const band = Math.max(1, Math.round(N / 64));
    function layer(mask, mat, o = {}) {
      const outline = o.outline !== false, shade = o.shade !== false;
      const ow = o.ow != null ? o.ow : band;
      const inside = new Uint8Array(N * N);
      for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) inside[j * N + i] = mask((i + 0.5) / k, (j + 0.5) / k) ? 1 : 0;
      const at = (a, i, j) => (i < 0 || j < 0 || i >= N || j >= N ? 0 : a[j * N + i]);
      const dist = (a, i, j, dx, dy, n) => { for (let s = 1; s <= n; s++) if (!at(a, i + dx * s, j + dy * s)) return true; return false; };
      const inner = new Uint8Array(N * N);
      for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
        if (!inside[j * N + i]) continue;
        const edge = outline && (dist(inside, i, j, 1, 0, ow) || dist(inside, i, j, -1, 0, ow) || dist(inside, i, j, 0, 1, ow) || dist(inside, i, j, 0, -1, ow));
        if (!edge) inner[j * N + i] = 1;
        else put(i, j, T.ink);
      }
      for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
        if (!inner[j * N + i]) continue;
        let c = mat.b;
        if (shade) {
          if (dist(inner, i, j, 0, -1, band) || dist(inner, i, j, -1, 0, band)) c = mat.l;
          else if (dist(inner, i, j, 0, 1, band) || dist(inner, i, j, 1, 0, band)) c = mat.d;
        }
        if (o.pattern) { const p = o.pattern(i / k, j / k, i, j); if (p) c = mat[p] || p; }
        put(i, j, c);
      }
    }
    function put(i, j, c, a = 255) { const p = (j * N + i) * 4; px[p] = c[0]; px[p + 1] = c[1]; px[p + 2] = c[2]; px[p + 3] = a; }
    function dot(u, v, c) { const i = Math.floor(u * k), j = Math.floor(v * k); for (let a = 0; a < band; a++) for (let b = 0; b < band; b++) if (i + a < N && j + b < N) put(i + a, j + b, c); }
    draw({ layer, dot, put, N, k, band });
    return { w: N, h: N, data: px };
  }

  function upscale(img, f) {
    const w = img.w * f, h = img.h * f, out = new Uint8ClampedArray(w * h * 4);
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) {
      const s = ((Math.floor(j / f) * img.w) + Math.floor(i / f)) * 4, d = (j * w + i) * 4;
      out[d] = img.data[s]; out[d + 1] = img.data[s + 1]; out[d + 2] = img.data[s + 2]; out[d + 3] = img.data[s + 3];
    }
    return { w, h, data: out };
  }

  // Pixel-Raster (für 16er-Favicons) → Bild.
  const PAL = {
    '.': null, I: T.ink, g: T.gold.l, G: T.gold.b, d: T.gold.d, r: T.rib.l, R: T.rib.b, x: T.rib.d,
    p: T.par.l, P: T.par.b, q: T.par.d, w: T.wood.l, W: T.wood.b, v: T.wood.d, s: T.stone.l, S: T.stone.b, t: T.stone.d,
    e: T.em.b, D: T.dia.b, n: T.night.b, i: T.iron.l, j: T.iron.b, k: T.iron.d,
  };
  function grid(rows) {
    const N = rows.length, px = new Uint8ClampedArray(N * N * 4);
    rows.forEach((row, y) => [...row].forEach((ch, x) => {
      const c = PAL[ch]; if (c === undefined) throw new Error('Farbe ' + ch); if (!c) return;
      const p = (y * N + x) * 4; px[p] = c[0]; px[p + 1] = c[1]; px[p + 2] = c[2]; px[p + 3] = 255;
    }));
    return { w: N, h: N, data: px };
  }

  // ---- Entwürfe -----------------------------------------------------------------------------------
  // lvl: 'big' (128 → 256), 'icon' (64), 'icon32' (32, vereinfacht), 'fav16' (16, nur Silhouette).
  const small = (lvl) => lvl === 'icon32' || lvl === 'fav16';

  // A: Kronen-Siegel – Goldrand, rotes Siegelband, Pergament-Scheibe, große Krone (ohne Schrift).
  function drawA(g, lvl) {
    const { layer, dot } = g;
    layer(circle(32, 32, 31.6), T.gold);
    if (lvl === 'fav16') {
      layer(circle(32, 32, 24), T.rib);
      layer(crown3(12, 52, 10, 34, 12), T.gold);
      return;
    }
    layer(circle(32, 32, 27.5), T.rib);
    layer(circle(32, 32, 24.5), T.par);
    if (lvl === 'big' || lvl === 'icon') for (let a = 0; a < 16; a++) { const t = (a / 16) * Math.PI * 2 + Math.PI / 16; dot(32 + Math.cos(t) * 29.7 - 0.5, 32 + Math.sin(t) * 29.7 - 0.5, T.gold.l); }
    const c = crownMasks(12, 52, 15, 36, 9);
    if (small(lvl)) layer(crown3(11, 53, 12, 35, 10), T.gold);
    else { layer(c.body, T.gold); layer(rect(12, 36, 52, 45), T.gold); }
    if (!small(lvl)) [[21.5, T.rib], [32, T.em], [42.5, T.dia]].forEach(([x, m]) => layer(rect(x - 2.5, 38, x + 2.5, 43), m));
    else [[21, T.rib], [32, T.em], [43, T.dia]].forEach(([x, m]) => layer(rect(x - 2, 38, x + 2, 42), m, { outline: false, shade: false }));
    if (!small(lvl)) c.tips.forEach(([x, y], i) => layer(circle(x, y - 0.5, i === 2 ? 3.4 : 2.8), T.rib));
  }

  // B: Wappenschild – Krone über dem Schild, Feld geteilt Rot | Gold, Silberrand, Turm von Kronstadt.
  function drawB(g, lvl) {
    const { layer } = g;
    const top = lvl === 'fav16' ? 17 : 16;
    const sh = shield(7, top, 57, 63.5);
    layer(sh, T.iron);
    const inner = shield(10.5, top + 3.5, 53.5, 59.5);
    layer(and(inner, rect(0, 0, 32.5, 64)), T.rib);
    layer(and(inner, rect(31.5, 0, 64, 64)), T.gold);
    if (lvl === 'fav16') { layer(crown3(16, 48, 25, 42, 9), T.gold); return; }
    // Turm
    const ty = lvl === 'fav16' ? 24 : 26;
    const merl = or(rect(21, ty - 4, 26, ty + 1), rect(29.5, ty - 4, 34.5, ty + 1), rect(38, ty - 4, 43, ty + 1));
    const towerM = or(merl, rect(21, ty, 43, ty + 4), rect(23, ty + 3, 41, 52));
    layer(towerM, T.stone, small(lvl) ? {} : {
      pattern: (u, v) => {
        if (v < ty + 5 || v > 51) return null;
        if (Math.floor(v) % 5 === 0) return 'd';
        const off = Math.floor(v / 5) % 2 === 0 ? 0 : 3;
        return Math.floor(u + off) % 6 === 0 ? 'd' : null;
      },
    });
    layer(or(rect(28, 41, 36, 52), circle(32, 41.5, 4)), T.woodD);
    if (!small(lvl)) layer(rect(30.5, ty + 7, 33.5, ty + 11), T.night, { outline: false, shade: false });
    // Krone über dem Schild
    const c = crownMasks(16, 48, 1, 13, 6);
    if (small(lvl)) layer(crown3(17, 47, 1, 12, 7), T.gold);
    else { layer(c.body, T.gold); layer(rect(16, 13, 48, 19), T.gold); }
    if (!small(lvl)) [[22, T.rib], [32, T.em], [42, T.dia]].forEach(([x, m]) => layer(rect(x - 2, 14.5, x + 2, 17.5), m));
    if (!small(lvl)) c.tips.forEach(([x, y], i) => layer(circle(x, y + 0.6, i === 2 ? 2.8 : 2.3), T.rib));
  }

  // C: Holz-Plakette – goldenes „K“ mit Krone, Eisenrahmen mit Nieten (ohne Schrift).
  function drawC(g, lvl) {
    const { layer } = g;
    layer(rrect(0.5, 0.5, 63.5, 63.5, 10), T.iron);
    const planks = (u, v, i, j) => {
      const col = Math.floor((u - 4) / 9.3);
      const x = (u - 4) - col * 9.3;
      if (x < 1) return 'd';
      const h = (col * 7919) % 13;
      if (Math.floor(v + h) % 11 === 0 && x > 2 && x < 7) return 'd';
      if (x > 7.6) return 'l';
      return null;
    };
    layer(rrect(4.5, 4.5, 59.5, 59.5, 7), T.wood, small(lvl) ? {} : { pattern: planks });
    if (!small(lvl)) [[9, 9], [55, 9], [9, 55], [55, 55]].forEach(([x, y]) => layer(circle(x, y, 2.7), T.iron));
    // „K“
    const big = lvl === 'fav16';
    const stem = big ? rect(13, 14, 27, 52) : rect(14, 19, 25, 53);
    const upper = big ? poly([[25, 38], [25, 30], [40, 14], [53, 14]]) : poly([[24, 38], [24, 33], [40, 19], [53, 19]]);
    const lower = big ? poly([[27, 32], [34, 27], [54, 52], [40, 52]]) : poly([[27, 34], [33, 30], [53, 53], [40, 53]]);
    layer(or(stem, upper, lower), T.gold);
    if (big) return;
    // kleine Krone auf dem Stamm
    const c = crownMasks(10.5, 28.5, 6, 14, 6);
    if (small(lvl)) layer(crown3(10.5, 28.5, 5, 14, 6), T.gold);
    else layer(or(c.body, rect(10.5, 14, 28.5, 20)), T.gold);
    if (!small(lvl)) c.tips.forEach(([x, y], i) => layer(circle(x, y + 0.3, i === 2 ? 2.2 : 1.8), T.rib));
  }

  // Handgezeichnete 16er-Favicons (A, B); C wird gerendert.
  const FAV16 = {
    A: ['.....IIIIII.....', '...IIGGGGGGII...', '..IgGRRRRRRGdI..', '.IgRRRRRRRRRRdI.',
        '.Ig' + 'gRRRggRRRg' + 'dI.', 'IgR' + 'gGRgGGdRGd' + 'RdI', 'IgR' + 'gGGGGGGGGd' + 'RdI', 'IgR' + 'gRGGeGGDGd' + 'RdI',
        'IgR' + 'dddddddddd' + 'RdI', 'IgR' + 'IIIIIIIIII' + 'RdI', 'IgRRRRRRRRRRRRdI', '.IgRRRRRRRRRRdI.',
        '.IdRRRRRRRRRRdI.', '..IddRRRRRRddI..', '...IIddddddII...', '.....IIIIII.....'],
    B: ['...' + 'g...gg...g' + '...', '...' + 'gG.gGGd.Gd' + '...', '...' + 'gRGGeGGDGd' + '...', '...' + 'dddddddddd' + '...',
        '.IIIIIIIIIIIIII.', '.IiRRRRRGGGGGkI.', '.IirRRRRgGGGGkI.', '.IirRRRRgGGGdkI.',
        '.IirRRRRgGGGdkI.', '.IjrRRRRgGGGdkI.', '..IjRRRRgGGGkI..', '..IjxRRRgGGdkI..',
        '...IjxRRGGdkI...', '....IjxRGdkI....', '.....IjxdkI.....', '......IIII......'],
  };

  const DESIGNS = { A: drawA, B: drawB, C: drawC };
  function build(id) {
    const d = DESIGNS[id];
    return {
      logo256: upscale(render(128, (g) => d(g, 'big')), 2),
      icon64: render(64, (g) => d(g, 'icon')),
      favicon32: render(32, (g) => d(g, 'icon32')),
      favicon16: FAV16[id] ? grid(FAV16[id]) : render(16, (g) => d(g, 'fav16')),
    };
  }
  root.KLOGO = { build, render, upscale, grid, T, FAV16, DESIGNS };
})(typeof window !== 'undefined' ? window : globalThis);
