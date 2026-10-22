#!/usr/bin/env node
// Procedural synthesis of the network's own sound pack (design concept §16 "Eigenes Sound-Pack" and
// "Villager-Stimme", stage 1 "Plapper-Stimme"). Everything is generated from code with a seeded PRNG, so
// re-running the tool reproduces the same audio.
//
//   cd tools/sounds && npm ci && node synth.mjs [--wav]
//
// Output: OGG Vorbis, mono, 44.1 kHz, loudness-normalised to about -16 LUFS (BS.1770 K-weighting, gated),
// written to helix-addon-guis-paper/src/pack/resources/sounds/<group>/<event...>/<variant>.ogg. The guis
// PackGenerator turns every directory holding OGGs into the sound event "reich:<dir.path.with.dots>".
// With --wav the intermediate WAVs are kept under tools/sounds/out/wav for listening.

import { createOggEncoder } from "wasm-media-encoders";
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SR = 44100;
const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, "../../helix-addon-guis-paper/src/pack/resources/sounds");
const WAV_OUT = join(HERE, "out/wav");
const KEEP_WAV = process.argv.includes("--wav");
const TARGET_LUFS = -16;

// ---------------------------------------------------------------------------------------------------------
// Basics: seeded random, buffers, envelopes, filters
// ---------------------------------------------------------------------------------------------------------

function rng(seed) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  next.range = (lo, hi) => lo + (hi - lo) * next();
  next.pick = (arr) => arr[Math.floor(next() * arr.length)];
  next.gauss = () => {
    let u = 0;
    for (let i = 0; i < 6; i++) u += next();
    return (u - 3) / Math.sqrt(0.5);
  };
  return next;
}

function hash(str) {
  let h = 2166136261;
  for (const c of str) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
  return h >>> 0;
}

const buf = (seconds) => new Float32Array(Math.max(1, Math.round(seconds * SR)));

function mixInto(dst, src, atSeconds = 0, gain = 1) {
  const off = Math.round(atSeconds * SR);
  for (let i = 0; i < src.length && off + i < dst.length; i++) if (off + i >= 0) dst[off + i] += src[i] * gain;
  return dst;
}

function noise(seconds, r) {
  const b = buf(seconds);
  for (let i = 0; i < b.length; i++) b[i] = r() * 2 - 1;
  return b;
}

/** Attack (linear) then exponential decay with time constant `tau`. */
function envAD(b, attack, tau) {
  const a = Math.max(1, attack * SR);
  for (let i = 0; i < b.length; i++) {
    const t = i / SR;
    b[i] *= i < a ? i / a : Math.exp(-(t - attack) / tau);
  }
  return b;
}

/** Short fade-in/out so files never start or end with a click. */
function fades(b, inS = 0.002, outS = 0.01) {
  const ni = Math.round(inS * SR), no = Math.round(outS * SR);
  for (let i = 0; i < ni && i < b.length; i++) b[i] *= i / ni;
  for (let i = 0; i < no && i < b.length; i++) b[b.length - 1 - i] *= i / no;
  return b;
}

/** RBJ biquad, `type` lowpass/highpass/bandpass/peak/highshelf. Processes in place. */
function biquad(b, type, f, q = 0.707, gainDb = 0) {
  const c = coeffs(type, f, q, gainDb);
  let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  for (let i = 0; i < b.length; i++) {
    const x = b[i];
    const y = c.b0 * x + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2;
    x2 = x1; x1 = x; y2 = y1; y1 = y;
    b[i] = y;
  }
  return b;
}

function coeffs(type, f, q, gainDb) {
  const w = (2 * Math.PI * Math.min(f, SR * 0.45)) / SR;
  const cw = Math.cos(w), sw = Math.sin(w), alpha = sw / (2 * q);
  const A = Math.pow(10, gainDb / 40);
  let b0, b1, b2, a0, a1, a2;
  switch (type) {
    case "lowpass": b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = b0; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
    case "highpass": b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = b0; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
    case "bandpass": b0 = alpha; b1 = 0; b2 = -alpha; a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha; break;
    case "peak": b0 = 1 + alpha * A; b1 = -2 * cw; b2 = 1 - alpha * A; a0 = 1 + alpha / A; a1 = -2 * cw; a2 = 1 - alpha / A; break;
    case "highshelf": {
      const s = 2 * Math.sqrt(A) * alpha;
      b0 = A * ((A + 1) + (A - 1) * cw + s); b1 = -2 * A * ((A - 1) + (A + 1) * cw); b2 = A * ((A + 1) + (A - 1) * cw - s);
      a0 = (A + 1) - (A - 1) * cw + s; a1 = 2 * ((A - 1) - (A + 1) * cw); a2 = (A + 1) - (A - 1) * cw - s;
      break;
    }
    default: throw new Error(type);
  }
  return { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0 };
}

/** Damped sinusoid ("modal" resonance) — the building block for wood, metal and bells. */
function mode(seconds, freq, tau, amp = 1, phase = 0, glide = 0) {
  const b = buf(seconds);
  let ph = phase;
  for (let i = 0; i < b.length; i++) {
    const t = i / SR;
    ph += (2 * Math.PI * freq * (1 + glide * Math.exp(-t / 0.01))) / SR;
    b[i] = amp * Math.sin(ph) * Math.exp(-t / tau);
  }
  return b;
}

/** Sine with a time-varying frequency function f(t) and amplitude function a(t). */
function sweep(seconds, f, a) {
  const b = buf(seconds);
  let ph = 0;
  for (let i = 0; i < b.length; i++) {
    const t = i / SR;
    ph += (2 * Math.PI * f(t)) / SR;
    b[i] = Math.sin(ph) * a(t);
  }
  return b;
}

/**
 * Additive harmonic synthesis with a time-varying spectral shape: `f0(t)` pitch, `amp(t)` level and
 * `shape(freq, t)` the gain of a harmonic at `freq`. Shape is re-evaluated every 64 samples, which is what
 * makes formants (voices) and brightness envelopes (brass) cheap and perfectly stable.
 */
function additive(seconds, f0, amp, shape, maxFreq = 9000) {
  const b = buf(seconds);
  const maxH = 256;
  const phases = new Float64Array(maxH);
  const gains = new Float64Array(maxH);
  let count = 0;
  for (let i = 0; i < b.length; i++) {
    const t = i / SR;
    const f = f0(t);
    if (i % 64 === 0) {
      count = Math.min(maxH, Math.floor(maxFreq / f));
      for (let k = 1; k <= count; k++) gains[k - 1] = shape(k * f, t, k);
    }
    let s = 0;
    for (let k = 1; k <= count; k++) {
      phases[k - 1] += (2 * Math.PI * k * f) / SR;
      s += gains[k - 1] * Math.sin(phases[k - 1]);
    }
    b[i] = s * amp(t);
  }
  return b;
}

const smooth = (x) => (x <= 0 ? 0 : x >= 1 ? 1 : x * x * (3 - 2 * x));
const lerp = (a, b, x) => a + (b - a) * x;
const midi = (n) => 440 * Math.pow(2, (n - 69) / 12);

// ---------------------------------------------------------------------------------------------------------
// Loudness (ITU-R BS.1770 K-weighting, gated integrated loudness) and normalisation
// ---------------------------------------------------------------------------------------------------------

function lufs(b) {
  const k = Float32Array.from(b);
  biquad(k, "highshelf", 1681.97, 0.7072, 3.9998);
  biquad(k, "highpass", 38.135, 0.5003);
  const block = Math.round(0.4 * SR), hop = Math.round(0.1 * SR);
  const powers = [];
  if (k.length <= block) {
    // Short sounds (UI): measure over the active part only (everything above -50 dB of the peak envelope).
    let peak = 0;
    for (const v of k) peak = Math.max(peak, Math.abs(v));
    let sum = 0, n = 0;
    const win = 256;
    for (let i = 0; i + win <= k.length; i += win) {
      let e = 0, p = 0;
      for (let j = i; j < i + win; j++) { e += k[j] * k[j]; p = Math.max(p, Math.abs(k[j])); }
      if (p > peak * 0.003) { sum += e; n += win; }
    }
    powers.push(n ? sum / n : 1e-12);
  } else {
    for (let i = 0; i + block <= k.length; i += hop) {
      let e = 0;
      for (let j = i; j < i + block; j++) e += k[j] * k[j];
      powers.push(e / block);
    }
  }
  const loud = (p) => -0.691 + 10 * Math.log10(p + 1e-15);
  let gated = powers.filter((p) => loud(p) > -70);
  if (!gated.length) return -70;
  const rel = loud(gated.reduce((a, c) => a + c, 0) / gated.length) - 10;
  gated = gated.filter((p) => loud(p) > rel);
  return loud(gated.reduce((a, c) => a + c, 0) / gated.length);
}

/** Gain to `target` LUFS; peaks above -1 dBFS are soft-limited (tanh) rather than clipped. */
function normalize(b, target = TARGET_LUFS) {
  const current = lufs(b);
  let gain = Math.pow(10, (target - current) / 20);
  let peak = 0;
  for (const v of b) peak = Math.max(peak, Math.abs(v));
  const ceiling = 0.89;
  if (peak * gain > ceiling * 2.2) gain = (ceiling * 2.2) / peak; // never drive the limiter too hard
  for (let i = 0; i < b.length; i++) {
    const x = b[i] * gain;
    b[i] = Math.abs(x) <= ceiling * 0.7 ? x : Math.sign(x) * (ceiling * 0.7 + ceiling * 0.3 * Math.tanh((Math.abs(x) - ceiling * 0.7) / (ceiling * 0.3)));
  }
  return { before: current, after: lufs(b) };
}

// ---------------------------------------------------------------------------------------------------------
// Output: WAV (optional) + OGG Vorbis
// ---------------------------------------------------------------------------------------------------------

let encoder;
const report = [];
let totalBytes = 0;

function wav(b) {
  const data = Buffer.alloc(44 + b.length * 2);
  data.write("RIFF", 0); data.writeUInt32LE(36 + b.length * 2, 4); data.write("WAVEfmt ", 8);
  data.writeUInt32LE(16, 16); data.writeUInt16LE(1, 20); data.writeUInt16LE(1, 22);
  data.writeUInt32LE(SR, 24); data.writeUInt32LE(SR * 2, 28); data.writeUInt16LE(2, 32); data.writeUInt16LE(16, 34);
  data.write("data", 36); data.writeUInt32LE(b.length * 2, 40);
  for (let i = 0; i < b.length; i++) data.writeInt16LE(Math.round(Math.max(-1, Math.min(1, b[i])) * 32767), 44 + i * 2);
  return data;
}

function ogg(b, serial) {
  encoder.configure({ sampleRate: SR, channels: 1, vbrQuality: 3 });
  const parts = [encoder.encode([b]).slice(), encoder.finalize().slice()];
  return fixSerial(Buffer.concat(parts.map((p) => Buffer.from(p))), serial);
}

const OGG_CRC = (() => {
  const t = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i << 24;
    for (let k = 0; k < 8; k++) c = c & 0x80000000 ? (c << 1) ^ 0x04c11db7 : c << 1;
    t[i] = c >>> 0;
  }
  return t;
})();

/**
 * libvorbis picks a random Ogg stream serial number; replace it with a fixed one (and re-checksum every
 * page) so re-running the tool produces byte-identical files and clean git diffs.
 */
function fixSerial(data, serial) {
  let pos = 0;
  while (pos + 27 <= data.length && data.toString("latin1", pos, pos + 4) === "OggS") {
    const segments = data[pos + 26];
    let size = 27 + segments;
    for (let i = 0; i < segments; i++) size += data[pos + 27 + i];
    data.writeUInt32LE(serial, pos + 14);
    data.writeUInt32LE(0, pos + 22);
    let crc = 0;
    for (let i = pos; i < pos + size; i++) crc = ((crc << 8) ^ OGG_CRC[((crc >>> 24) ^ data[i]) & 0xff]) >>> 0;
    data.writeUInt32LE(crc, pos + 22);
    pos += size;
  }
  if (pos !== data.length) throw new Error("unexpected Ogg page layout");
  return data;
}

/** Writes one variant: `path` like "ui/click/1" → sounds/ui/click/1.ogg. */
function emit(path, b, target = TARGET_LUFS) {
  fades(b);
  const { after } = normalize(b, target);
  const file = join(OUT, `${path}.ogg`);
  mkdirSync(dirname(file), { recursive: true });
  const bytes = ogg(b, hash(path));
  writeFileSync(file, bytes);
  totalBytes += bytes.length;
  if (KEEP_WAV) {
    const w = join(WAV_OUT, `${path}.wav`);
    mkdirSync(dirname(w), { recursive: true });
    writeFileSync(w, wav(b));
  }
  report.push({ path, seconds: b.length / SR, lufs: after, bytes: bytes.length });
}

// ---------------------------------------------------------------------------------------------------------
// UI sounds (< 0.3 s, 3 variants each)
// ---------------------------------------------------------------------------------------------------------

/** Dry wooden click: a tiny noise tick exciting three fast-decaying wood modes. */
function woodClick(r) {
  const len = 0.09;
  const out = buf(len);
  const base = r.range(850, 1050);
  for (const [ratio, tau, amp] of [[1, 0.012, 1], [2.31, 0.007, 0.6], [3.9, 0.004, 0.35]]) {
    mixInto(out, mode(len, base * ratio * r.range(0.97, 1.03), tau * r.range(0.85, 1.15), amp, r() * 6.28));
  }
  const tick = envAD(noise(len, r), 0.0003, 0.0018);
  biquad(tick, "bandpass", r.range(2800, 3600), 1.2);
  mixInto(out, tick, 0, 2.2);
  return biquad(out, "highpass", 180);
}

/** Page turn: a swish of filtered noise whose band sweeps upward, plus the paper "flap" at the end. */
function pageTurn(r) {
  const len = r.range(0.24, 0.28);
  const n = noise(len, r);
  const out = buf(len);
  const lo = r.range(900, 1300), hi = r.range(3500, 4800);
  const seg = 256;
  for (let s = 0; s < n.length; s += seg) {
    const t = s / n.length;
    const part = n.slice(s, s + seg);
    biquad(part, "bandpass", lerp(lo, hi, t), 0.9);
    out.set(part, s);
  }
  // swish envelope: rise, sustain, then a flap
  for (let i = 0; i < out.length; i++) {
    const t = i / out.length;
    const crackle = 1 + 0.6 * (r() < 0.02 ? r() : 0);
    out[i] *= Math.sin(Math.PI * Math.pow(t, 0.8)) * crackle;
  }
  const flap = envAD(noise(0.05, r), 0.002, 0.012);
  biquad(flap, "bandpass", r.range(500, 800), 1.4);
  mixInto(out, flap, len * r.range(0.72, 0.8), 1.4);
  return biquad(out, "highpass", 300);
}

/** Parchment rustle: sparse crackles (random grains) over a soft noise bed. */
function parchment(r) {
  const len = r.range(0.24, 0.28);
  const bed = noise(len, r);
  biquad(bed, "bandpass", r.range(2500, 3500), 0.7);
  for (let i = 0; i < bed.length; i++) bed[i] *= 0.25 * Math.sin((Math.PI * i) / bed.length);
  const grains = 18 + Math.floor(r() * 10);
  for (let g = 0; g < grains; g++) {
    const at = len * Math.pow(r(), 0.9) * 0.92;
    const grain = envAD(noise(0.012, r), 0.0004, r.range(0.0012, 0.003));
    biquad(grain, "bandpass", r.range(2200, 7000), r.range(1.5, 4));
    mixInto(bed, grain, at, r.range(0.4, 1.2) * Math.sin((Math.PI * at) / len));
  }
  return biquad(bed, "highpass", 600);
}

/** Wax seal pressed: a soft low thump (pitch drops) and a short squishy contact noise. */
function sealStamp(r) {
  const len = 0.26;
  const out = buf(len);
  const f = r.range(115, 140);
  mixInto(out, sweep(len, (t) => f * (0.6 + 0.4 * Math.exp(-t / 0.03)), (t) => Math.exp(-t / 0.05)), 0, 1);
  mixInto(out, mode(len, r.range(320, 380), 0.03, 0.35));
  const squish = envAD(noise(0.08, r), 0.004, 0.02);
  biquad(squish, "lowpass", r.range(1200, 1600), 0.8);
  mixInto(out, squish, 0.003, 0.7);
  const press = envAD(noise(0.03, r), 0.001, 0.004);
  biquad(press, "bandpass", 2500, 2);
  mixInto(out, press, 0, 0.4);
  return out;
}

/** Coins: three to four small metallic pings (inharmonic partials) dropping onto each other. */
function coins(r) {
  const len = 0.29;
  const out = buf(len);
  const hits = 3 + Math.floor(r() * 2);
  let at = 0;
  for (let h = 0; h < hits; h++) {
    const base = r.range(2300, 3400);
    const amp = h === 0 ? 1 : r.range(0.45, 0.8);
    for (const [ratio, tau] of [[1, 0.06], [1.62, 0.045], [2.41, 0.03], [3.27, 0.02]]) {
      mixInto(out, mode(len - at, base * ratio, tau * r.range(0.8, 1.2), amp / ratio, r() * 6.28), at);
    }
    const clink = envAD(noise(0.01, r), 0.0002, 0.001);
    biquad(clink, "highpass", 5000);
    mixInto(out, clink, at, amp * 0.8);
    at += r.range(0.035, 0.065);
  }
  return biquad(out, "highpass", 1200);
}

/** Error: dull wooden thud — low, muffled body modes and very little attack. */
function errorThud(r) {
  const len = 0.22;
  const out = buf(len);
  const base = r.range(140, 175);
  for (const [ratio, tau, amp] of [[1, 0.06, 1], [1.73, 0.035, 0.6], [2.6, 0.02, 0.35]]) {
    mixInto(out, mode(len, base * ratio, tau, amp, 0, 0.15));
  }
  const knock = envAD(noise(0.04, r), 0.0008, 0.008);
  biquad(knock, "lowpass", 900, 0.7);
  mixInto(out, knock, 0, 1.2);
  return biquad(out, "lowpass", 1400, 0.6);
}

// ---------------------------------------------------------------------------------------------------------
// MineClash
// ---------------------------------------------------------------------------------------------------------

/** Card played: a round "plop" — a sine whose pitch springs upward, with a tiny bubble tail. */
function cardPlop(r) {
  const len = 0.16;
  const f0 = r.range(260, 320), f1 = r.range(620, 760);
  const out = sweep(len, (t) => lerp(f0, f1, smooth(t / 0.05)), (t) => Math.min(1, t / 0.003) * Math.exp(-t / 0.035));
  mixInto(out, sweep(0.06, (t) => f1 * 1.5 + t * 3000, (t) => Math.exp(-t / 0.012) * 0.25), 0.035);
  const snap = envAD(noise(0.02, r), 0.0003, 0.002);
  biquad(snap, "bandpass", 3000, 1.5);
  mixInto(out, snap, 0, 0.5);
  return out;
}

/** Tower hit: a heavy thump with crunching stone debris. */
function towerHit(r) {
  const len = 0.32;
  const out = buf(len);
  const f = r.range(70, 90);
  mixInto(out, sweep(len, (t) => f * (1 + 1.2 * Math.exp(-t / 0.02)), (t) => Math.exp(-t / 0.07)), 0, 1.1);
  const crunch = envAD(noise(len, r), 0.001, 0.05);
  biquad(crunch, "bandpass", r.range(700, 1000), 0.8);
  mixInto(out, crunch, 0, 1.2);
  for (let i = 0; i < 6; i++) {
    const grit = envAD(noise(0.02, r), 0.0005, 0.004);
    biquad(grit, "bandpass", r.range(1500, 4000), 2);
    mixInto(out, grit, r.range(0.01, 0.2), r.range(0.2, 0.6));
  }
  return out;
}

/** Tower falls: a cascade of impacts over a long, low rumble. */
function towerFall(r) {
  const len = 1.8;
  const out = buf(len);
  const rumble = noise(len, r);
  biquad(rumble, "lowpass", 180, 0.8);
  biquad(rumble, "lowpass", 220, 0.7);
  for (let i = 0; i < rumble.length; i++) {
    const t = i / SR;
    rumble[i] *= Math.min(1, t / 0.08) * Math.exp(-t / 0.7) * 4;
  }
  mixInto(out, rumble);
  let at = 0;
  while (at < 1.2) {
    const hit = towerHit(r);
    mixInto(out, hit, at, Math.exp(-at / 0.6) * r.range(0.5, 1));
    at += r.range(0.06, 0.2);
  }
  // final settling debris
  for (let i = 0; i < 14; i++) {
    const grit = envAD(noise(0.03, r), 0.0005, 0.006);
    biquad(grit, "bandpass", r.range(1200, 3500), 2);
    mixInto(out, grit, r.range(0.6, 1.6), r.range(0.1, 0.3));
  }
  return out;
}

/** A small struck bell (inharmonic bell partials). */
function bell(len, f, amp = 1, r = Math.random) {
  const out = buf(len);
  for (const [ratio, tau, a] of [[0.5, 0.9, 0.35], [1, 0.7, 1], [1.19, 0.5, 0.4], [1.5, 0.4, 0.3], [2.0, 0.35, 0.35], [2.76, 0.2, 0.25], [5.4, 0.08, 0.12]]) {
    mixInto(out, mode(len, f * ratio, tau, a * amp, r() * 6.28));
  }
  return out;
}

/** Chest ready: a little two-strike bell "ding-ding". */
function chestReady(r) {
  const len = 1.1;
  const out = buf(len);
  const f = r.range(1500, 1650);
  mixInto(out, bell(len, f, 1, r));
  mixInto(out, bell(len - 0.14, f * 1.335, 0.8, r), 0.14);
  return biquad(out, "highpass", 400);
}

/** Chest open: lid creak (pulse train through wooden resonances), latch click, then a sparkle shimmer. */
function chestOpen(r) {
  const len = 1.25;
  const out = buf(len);
  const latch = woodClick(r);
  mixInto(out, latch, 0, 1.2);
  // creak: a slowly varying pulse train
  const creakLen = 0.45;
  const creak = buf(creakLen);
  let ph = 0;
  const base = r.range(45, 60);
  for (let i = 0; i < creak.length; i++) {
    const t = i / SR;
    const rate = base * (1 + 0.6 * Math.sin(2 * Math.PI * 2.2 * t) + 0.4 * t);
    ph += rate / SR;
    if (ph >= 1) { ph -= 1; creak[i] = 1; }
  }
  const body = Float32Array.from(creak);
  biquad(body, "bandpass", r.range(600, 800), 6);
  const body2 = Float32Array.from(creak);
  biquad(body2, "bandpass", r.range(1500, 1900), 8);
  for (let i = 0; i < creak.length; i++) creak[i] = (body[i] + 0.6 * body2[i]) * Math.sin((Math.PI * i) / creak.length);
  mixInto(out, creak, 0.05, 3);
  // sparkle: rising little bell arpeggio
  const notes = [84, 88, 91, 96];
  notes.forEach((n, i) => mixInto(out, bell(0.6, midi(n) * 0.5 * r.range(0.995, 1.005), 0.35, r), 0.45 + i * 0.07));
  return biquad(out, "highpass", 120);
}

/** Brass-like tone: additive harmonics whose brightness opens with the attack ("blat"). */
function brass(len, f0fn, ampfn, brightness = 1, vibrato = 0) {
  return additive(len, (t) => f0fn(t) * (1 + vibrato * Math.sin(2 * Math.PI * 5.5 * t) * smooth(t / 0.3)), ampfn, (freq, t) => {
    const open = brightness * (0.35 + 0.65 * smooth(t / 0.06));
    const cutoff = 600 + 2600 * open;
    return Math.exp(-freq / cutoff) * (freq < 250 ? freq / 250 : 1);
  }, 7000);
}

function note(len, attack = 0.02, release = 0.08) {
  return (t) => Math.min(1, t / attack) * (t > len - release ? Math.max(0, (len - t) / release) : 1) * (1 - 0.15 * smooth(t / len));
}

/** Victory: short fanfare — triplet pickup into a held major chord. */
function victory() {
  const out = buf(2.3);
  const seq = [[0, 67, 0.13], [0.14, 67, 0.13], [0.28, 67, 0.13], [0.42, 72, 0.5], [0.95, 67, 0.22], [1.18, 72, 1.0]];
  for (const [at, n, len] of seq) mixInto(out, brass(len, () => midi(n), note(len, 0.015, 0.06), 1, 0.004), at, 0.8);
  // chord under the last note
  for (const n of [64, 67, 76]) mixInto(out, brass(1.0, () => midi(n), note(1.0, 0.04, 0.25), 0.8, 0.004), 1.18, 0.35);
  // a timpani-ish boom
  mixInto(out, sweep(0.8, (t) => 70 * (1 + 0.3 * Math.exp(-t / 0.05)), (t) => Math.exp(-t / 0.25)), 1.18, 0.6);
  return out;
}

/** Defeat: the sad trombone "wah wah wah waaah". */
function defeat() {
  const out = buf(2.6);
  const seq = [[0, 55, 0.36], [0.4, 54, 0.36], [0.8, 53, 0.36], [1.2, 52, 1.3]];
  for (const [at, n, len] of seq) {
    const last = len > 1;
    const tone = brass(len, (t) => midi(n) * (1 - 0.012 * smooth(t / len)), note(len, 0.03, last ? 0.4 : 0.08), 0.55, last ? 0.018 : 0.003);
    // plunger "wah": brightness modulation by amplitude shaping
    for (let i = 0; i < tone.length; i++) {
      const t = i / SR;
      const wah = last ? 0.65 + 0.35 * Math.sin(2 * Math.PI * 3 * t - 1.5) : 0.5 + 0.5 * Math.sin(Math.PI * Math.min(1, t / (len * 0.7)));
      tone[i] *= wah;
    }
    biquad(tone, "lowpass", 1400, 0.9);
    mixInto(out, tone, at);
  }
  return out;
}

/** Energy countdown: a short, high woodblock tick. */
function energyTick(r) {
  const len = 0.07;
  const out = buf(len);
  const f = r.range(1750, 1900);
  mixInto(out, mode(len, f, 0.015, 1));
  mixInto(out, mode(len, f * 2.7, 0.006, 0.3));
  const tick = envAD(noise(len, r), 0.0002, 0.0012);
  biquad(tick, "highpass", 3000);
  mixInto(out, tick, 0, 0.8);
  return out;
}

// ---------------------------------------------------------------------------------------------------------
// Villager voices: formant "babble" syllables + the signature "Hmm"/"Hrm" (§16 Villager-Stimme, stage 1)
// ---------------------------------------------------------------------------------------------------------

// Vowel formants (F1, F2, F3 in Hz, adult male reference; scaled per voice by `tract`).
const VOWELS = {
  a: [730, 1090, 2440], e: [400, 2000, 2600], i: [290, 2250, 2900], o: [450, 800, 2600],
  u: [325, 700, 2400], ae: [600, 1800, 2500], oe: [400, 1550, 2350], ue: [280, 1650, 2250],
};

// Onsets: what precedes the vowel. kind: none | stop (burst) | nasal | fric | glide
const ONSETS = [
  { id: "", kind: "none" },
  { id: "b", kind: "stop", voiced: true, burst: 900, locus: 0.7 },
  { id: "d", kind: "stop", voiced: true, burst: 3000, locus: 1.1 },
  { id: "g", kind: "stop", voiced: true, burst: 2000, locus: 1.3 },
  { id: "p", kind: "stop", burst: 1000, locus: 0.7 },
  { id: "t", kind: "stop", burst: 4200, locus: 1.1 },
  { id: "k", kind: "stop", burst: 2300, locus: 1.3 },
  { id: "m", kind: "nasal", locus: 0.7 },
  { id: "n", kind: "nasal", locus: 1.1 },
  { id: "l", kind: "glide", locus: 1.0 },
  { id: "w", kind: "glide", locus: 0.6 },
  { id: "s", kind: "fric", band: 6000 },
  { id: "sch", kind: "fric", band: 3200 },
  { id: "h", kind: "fric", band: 1500, breath: true },
];

/**
 * Voice profiles (design §12 characters, §16 Stimmprofil). f0 in Hz, tempo scales syllable length (<1 =
 * faster), tract scales formants (<1 = bigger/deeper head), contour: per-syllable pitch movement,
 * breath/rasp add noise and roughness, bright tilts the spectrum, loud is the target loudness offset.
 */
const VOICES = {
  bertold:    { f0: 108, tempo: 1.35, tract: 0.9,  contour: "pompous",  breath: 0.05, rasp: 0.04, bright: 0.9, loud: 0,  count: 30, hmm: "hmm" },
  mira:       { f0: 235, tempo: 0.68, tract: 1.15, contour: "lively",   breath: 0.04, rasp: 0,    bright: 1.2, loud: 0,  count: 36, hmm: "hmm" },
  gorm:       { f0: 128, tempo: 0.85, tract: 0.92, contour: "shout",    breath: 0.08, rasp: 0.18, bright: 1.25, loud: 3, count: 28, hmm: "hrm" },
  elsa:       { f0: 205, tempo: 1.0,  tract: 1.1,  contour: "level",    breath: 0.06, rasp: 0,    bright: 1.0, loud: 0,  count: 30, hmm: "hmm" },
  klunk:      { f0: 74,  tempo: 0.62, tract: 0.84, contour: "falling",  breath: 0.05, rasp: 0.1,  bright: 0.7, loud: 0,  count: 20, hmm: "hrm" },
  pitt:       { f0: 158, tempo: 0.82, tract: 1.0,  contour: "bouncy",   breath: 0.05, rasp: 0.03, bright: 1.1, loud: 0,  count: 30, hmm: "hmm" },
  hedda:      { f0: 188, tempo: 0.95, tract: 1.06, contour: "level",    breath: 0.03, rasp: 0,    bright: 0.95, loud: 0, count: 28, hmm: "hrm", nasal: 0.35 },
  funkel:     { f0: 168, tempo: 0.74, tract: 1.0,  contour: "erratic",  breath: 0.07, rasp: 0.02, bright: 1.05, loud: 0, count: 34, hmm: "hmm" },
  ambo:       { f0: 96,  tempo: 0.8,  tract: 0.88, contour: "falling",  breath: 0.06, rasp: 0.08, bright: 0.85, loud: 0, count: 26, hmm: "hrm" },
  knoepfchen: { f0: 320, tempo: 0.8,  tract: 1.25, contour: "question", breath: 0.05, rasp: 0,    bright: 1.1, loud: 0,  count: 32, hmm: "hmm" },
  // CityBuild figures (§12 "CityBuild-Figuren")
  lotte:      { f0: 262, tempo: 0.7,  tract: 1.18, contour: "bouncy",   breath: 0.04, rasp: 0,    bright: 1.2, loud: 0,  count: 32, hmm: "hmm" },
  gundula:    { f0: 196, tempo: 1.05, tract: 1.05, contour: "level",    breath: 0.03, rasp: 0,    bright: 0.9, loud: 0,  count: 28, hmm: "hrm", nasal: 0.25 },
  fritz:      { f0: 140, tempo: 0.72, tract: 0.95, contour: "shout",    breath: 0.06, rasp: 0.06, bright: 1.25, loud: 2, count: 32, hmm: "hmm" },
  ludwig:     { f0: 122, tempo: 0.6,  tract: 0.94, contour: "pompous",  breath: 0.04, rasp: 0.02, bright: 1.1, loud: 0,  count: 32, hmm: "hmm" },
  henni:      { f0: 210, tempo: 0.66, tract: 1.04, contour: "falling",  breath: 0.05, rasp: 0.05, bright: 1.05, loud: 0, count: 26, hmm: "hrm" },
  waldemar:   { f0: 92,  tempo: 1.4,  tract: 0.88, contour: "level",    breath: 0.12, rasp: 0.02, bright: 0.75, loud: -2, count: 24, hmm: "hmm" },
  rupert:     { f0: 134, tempo: 0.95, tract: 0.96, contour: "level",    breath: 0.04, rasp: 0.03, bright: 0.95, loud: 0, count: 28, hmm: "hrm" },
  paula:      { f0: 285, tempo: 0.55, tract: 1.2,  contour: "lively",   breath: 0.12, rasp: 0,    bright: 1.15, loud: 0, count: 34, hmm: "hmm" },
  brunhilde:  { f0: 176, tempo: 0.85, tract: 0.98, contour: "shout",    breath: 0.07, rasp: 0.12, bright: 1.2, loud: 2,  count: 28, hmm: "hrm" },
  greta:      { f0: 228, tempo: 1.1,  tract: 1.08, contour: "erratic",  breath: 0.16, rasp: 0.02, bright: 0.85, loud: -3, count: 28, hmm: "hmm" },
  // CityBuild dealers (A21.8)
  kuno:       { f0: 118, tempo: 0.8,  tract: 0.92, contour: "level",    breath: 0.05, rasp: 0.07, bright: 1.0, loud: 0,  count: 30, hmm: "hrm" },
  tilda:      { f0: 238, tempo: 1.0,  tract: 1.1,  contour: "pompous",  breath: 0.1,  rasp: 0,    bright: 1.05, loud: -1, count: 28, hmm: "hmm" },
  frieda:     { f0: 214, tempo: 0.75, tract: 1.06, contour: "bouncy",   breath: 0.06, rasp: 0.02, bright: 1.1, loud: 0,  count: 30, hmm: "hmm" },
};

/** Pitch movement across one syllable (x = 0..1), as a frequency ratio. */
function contourFn(kind, r) {
  const a = r.range(-1, 1);
  switch (kind) {
    case "pompous": return (x) => 1 + 0.06 * Math.sin(Math.PI * x) + 0.02 * a;
    case "lively": return (x) => 1 + 0.1 * a + 0.08 * Math.sin(Math.PI * x * 1.5);
    case "shout": return (x) => 1.08 + 0.12 * Math.sin(Math.PI * Math.min(1, x * 1.4)) - 0.1 * x;
    case "falling": return (x) => 1 + 0.04 * a - 0.14 * x;
    case "bouncy": return (x) => 1 + 0.12 * Math.sin(Math.PI * x) + 0.05 * a;
    case "erratic": return (x) => 1 + 0.18 * a + (x > 0.5 ? 0.15 * Math.sign(a) : 0);
    case "question": return (x) => 1 + 0.05 * a + 0.28 * smooth((x - 0.35) / 0.65);
    default: return (x) => 1 + 0.03 * a - 0.03 * x;
  }
}

/** Gain of one harmonic at `freq` given formants F (Hz) and bandwidths. Lorentzian resonance sum. */
function formantGain(freq, F, bright, nasal = 0) {
  const bw = [90, 120, 170];
  const amps = [1, 0.55 * bright, 0.28 * bright];
  let g = 0;
  for (let i = 0; i < 3; i++) {
    const d = (freq - F[i]) / (bw[i] / 2 + F[i] * 0.04);
    g += amps[i] / (1 + d * d);
  }
  if (nasal) {
    // nasal murmur: low resonance at ~250 Hz, anti-resonance near 1 kHz
    const dn = (freq - 260) / 80;
    g = g * (1 - nasal * 0.6 / (1 + ((freq - 1000) / 200) ** 2)) + nasal * 0.8 / (1 + dn * dn);
  }
  // glottal source tilt (-12 dB/oct above 300 Hz-ish), brighter voices tilt less
  const tilt = 1 / (1 + Math.pow(freq / (350 * bright), 1.3));
  return g * tilt * 3;
}

/** One babble syllable for voice `v`: onset consonant + vowel, with formant transitions and pitch contour. */
function syllable(v, onset, vowel, r) {
  const T = (r.range(0.11, 0.17)) * v.tempo;
  const onsetLen = onset.kind === "fric" ? 0.05 * Math.sqrt(v.tempo) : onset.kind === "nasal" ? 0.04 : onset.kind === "stop" ? 0.015 : 0;
  const len = onsetLen + T + 0.03;
  const out = buf(len);
  const target = VOWELS[vowel].map((f) => f * v.tract);
  const start = onset.kind === "none" || onset.kind === "fric"
    ? target
    : [target[0] * 0.55, lerp(target[1], 1700 * v.tract * onset.locus, 0.55), target[2]];
  const contour = contourFn(v.contour, r);
  const f0 = v.f0 * r.range(0.95, 1.05);
  const vStart = onset.kind === "nasal" ? 0 : onsetLen;
  const jitter = rng(hash(`${vowel}${onset.id}${f0}`));
  let jit = 1;

  const voiced = additive(len - vStart, (t) => {
    if ((t * SR) % 64 < 1) jit = 1 + v.rasp * 0.06 * jitter.gauss();
    const x = Math.min(1, t / (len - vStart));
    return f0 * contour(x) * jit;
  }, (t) => {
    const tt = t + vStart;
    const vowelT = tt - onsetLen;
    const attack = onset.kind === "nasal" ? Math.min(1, t / 0.01) * (tt < onsetLen ? 0.55 : 1) : Math.min(1, t / 0.012);
    const release = vowelT > T ? Math.max(0, 1 - (vowelT - T) / 0.03) : 1;
    const rough = 1 + v.rasp * Math.sin(2 * Math.PI * f0 * 0.5 * t) * 0.8;
    return attack * release * rough * (1 - 0.25 * smooth(vowelT / T));
  }, (freq, t) => {
    const tt = t + vStart;
    if (onset.kind === "nasal" && tt < onsetLen) return formantGain(freq, [250, 1200, 2400], v.bright, 1) * 0.6;
    const x = smooth((tt - onsetLen) / 0.045);
    const F = start.map((s, i) => lerp(s, target[i], x));
    return formantGain(freq, F, v.bright, v.nasal || 0);
  }, 6500);
  mixInto(out, voiced, vStart);

  // consonant noise
  if (onset.kind === "stop") {
    const burst = envAD(noise(0.03, r), 0.0005, onset.voiced ? 0.004 : 0.008);
    biquad(burst, "bandpass", onset.burst * Math.sqrt(v.tract), 1.2);
    mixInto(out, burst, onsetLen * 0.3, onset.voiced ? 0.25 : 0.5);
  } else if (onset.kind === "fric") {
    const fr = noise(onsetLen + 0.02, r);
    biquad(fr, onset.breath ? "lowpass" : "bandpass", onset.band, onset.breath ? 0.7 : 1.5);
    for (let i = 0; i < fr.length; i++) fr[i] *= Math.sin((Math.PI * i) / fr.length);
    mixInto(out, fr, 0, onset.breath ? 0.4 : 0.22);
  }
  // breath on the vowel
  if (v.breath > 0) {
    const br = noise(len, r);
    biquad(br, "bandpass", target[1], 1.2);
    for (let i = 0; i < br.length; i++) br[i] *= v.breath * Math.min(1, i / (0.02 * SR)) * Math.max(0, 1 - i / br.length);
    mixInto(out, br);
  }
  return biquad(out, "highpass", Math.max(60, v.f0 * 0.6));
}

/** The signature "Hmm" (closed mouth hum) or "Hrm" (gruff, with a short rolled r) in the voice's pitch. */
function hum(v, r) {
  const gruff = v.hmm === "hrm";
  const len = (gruff ? 0.38 : 0.48) * Math.sqrt(v.tempo);
  const question = v.contour === "question";
  const f0 = v.f0 * (gruff ? 0.9 : 1);
  const out = additive(len, (t) => {
    const x = t / len;
    if (question) return f0 * (0.95 + 0.3 * smooth((x - 0.4) / 0.6));
    return f0 * (1.04 + 0.08 * Math.sin(Math.PI * Math.min(1, x * 1.6)) - 0.14 * x);
  }, (t) => {
    const x = t / len;
    const env = Math.min(1, t / 0.03) * (x > 0.7 ? Math.max(0, (1 - x) / 0.3) : 1);
    // "Hrm": the r is a ~28 Hz flutter in the first third
    const flutter = gruff && x < 0.4 ? 0.55 + 0.45 * Math.sin(2 * Math.PI * 28 * t) : 1;
    return env * flutter * (1 + v.rasp * 0.5 * Math.sin(2 * Math.PI * f0 * 0.5 * t));
  }, (freq, t) => {
    const x = t / len;
    if (gruff && x < 0.3) {
      // open "r" vowel colour before closing to the hum
      const F = VOWELS.oe.map((f) => f * v.tract * 0.9);
      return lerp(formantGain(freq, F, v.bright * 0.8), formantGain(freq, [260 * v.tract, 1100, 2300], v.bright, 1), smooth(x / 0.3));
    }
    return formantGain(freq, [250 * v.tract, 1100 * v.tract, 2300 * v.tract], v.bright * 0.8, 1);
  }, 4000);
  // the "H": breath before the hum
  const h = noise(0.06, r);
  biquad(h, "lowpass", 1500, 0.7);
  for (let i = 0; i < h.length; i++) h[i] *= 0.18 * Math.sin((Math.PI * i) / h.length);
  const full = buf(len + 0.04);
  mixInto(full, h, 0);
  mixInto(full, out, 0.035);
  return biquad(full, "highpass", Math.max(55, f0 * 0.5));
}

/** 20–40 distinct consonant+vowel syllables per voice, picked deterministically from the voice's seed. */
function babbleSet(id, v) {
  const r = rng(hash(`babble:${id}`));
  const vowels = Object.keys(VOWELS);
  const seen = new Set();
  const out = [];
  while (out.length < v.count) {
    const onset = r() < 0.15 ? ONSETS[0] : r.pick(ONSETS.slice(1));
    // characters lean on their favourite vowels: Klunk/Ambo dark (o, u, a), Knöpfchen/Mira bright (i, e, ae)
    let vowel = r.pick(vowels);
    if (v.tract < 0.9 && r() < 0.5) vowel = r.pick(["o", "u", "a"]);
    if (v.tract > 1.1 && r() < 0.5) vowel = r.pick(["i", "e", "ae", "ue"]);
    const key = onset.id + vowel;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ onset, vowel, r: rng(hash(`${id}:${key}`)) });
  }
  return out;
}

// ---------------------------------------------------------------------------------------------------------
// Render everything
// ---------------------------------------------------------------------------------------------------------

encoder = await createOggEncoder();
rmSync(OUT, { recursive: true, force: true });
if (KEEP_WAV) rmSync(WAV_OUT, { recursive: true, force: true });

const variants = (path, fn, n = 3) => {
  for (let i = 1; i <= n; i++) emit(`${path}/${i}`, fn(rng(hash(`${path}#${i}`))));
};

variants("ui/click", woodClick);
variants("ui/page", pageTurn);
variants("ui/rustle", parchment);
variants("ui/stamp", sealStamp);
variants("ui/coins", coins);
variants("ui/error", errorThud);

variants("mineclash/card_play", cardPlop);
variants("mineclash/tower_hit", towerHit);
variants("mineclash/tower_fall", towerFall, 2);
variants("mineclash/chest_ready", chestReady, 1);
variants("mineclash/chest_open", chestOpen, 2);
emit("mineclash/victory/1", victory());
emit("mineclash/defeat/1", defeat());
variants("mineclash/energy_tick", energyTick);

for (const [id, v] of Object.entries(VOICES)) {
  babbleSet(id, v).forEach(({ onset, vowel, r }, i) => {
    emit(`villager/${id}/babble/${String(i + 1).padStart(2, "0")}`, syllable(v, onset, vowel, r), TARGET_LUFS + v.loud);
  });
  emit(`villager/${id}/hmm/1`, hum(v, rng(hash(`hmm:${id}`))), TARGET_LUFS + v.loud);
}

const ui = report.filter((e) => e.path.startsWith("ui/"));
const longestUi = Math.max(...ui.map((e) => e.seconds));
const avg = report.reduce((a, e) => a + e.lufs, 0) / report.length;
console.log(`${report.length} files, ${(totalBytes / 1024).toFixed(0)} KiB, longest UI sound ${longestUi.toFixed(3)} s, mean ${avg.toFixed(1)} LUFS`);
if (process.argv.includes("--verbose")) for (const e of report) console.log(`${e.path.padEnd(36)} ${e.seconds.toFixed(3)} s ${e.lufs.toFixed(1)} LUFS ${e.bytes} B`);
if (longestUi >= 0.3) throw new Error("UI sounds must be shorter than 0.3 s");
