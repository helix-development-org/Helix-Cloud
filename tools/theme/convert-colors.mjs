#!/usr/bin/env node
// Converts literal hex MiniMessage colour tags (<#ffcf4a> … </#ffcf4a>) into theme colour tokens
// (<tc:accent> … </tc:accent>, see docs/THEMES.md) in lang JSON files and Kotlin string literals.
//
//   node tools/theme/convert-colors.mjs           convert in place and print a report
//   node tools/theme/convert-colors.mjs --check   change nothing; exit 1 if a mapped hex colour remains
//
// Scope: */src/main/resources/lang/**/*.json and */src/main/kotlin/**/*.kt of the repository, minus the
// modules in token-map.json "excludeModules" and the Kotlin paths in "skipKotlin" (texts there are still
// rendered without token expansion). Kotlin comment lines (KDoc, //) are left alone. Unmapped hex stays.
import { readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

const here = fileURLToPath(new URL(".", import.meta.url));
const root = join(here, "..", "..");
const config = JSON.parse(readFileSync(join(here, "token-map.json"), "utf8"));
const map = config.map;
const check = process.argv.includes("--check");
const TAG = /<(\/?)#([0-9a-fA-F]{6})>/g;

function walk(dir, out) {
  for (const name of readdirSync(dir)) {
    if (name === "build" || name === "node_modules" || name.startsWith(".")) continue;
    const path = join(dir, name);
    const stat = statSync(path);
    if (stat.isDirectory()) walk(path, out);
    else out.push(path);
  }
  return out;
}

const rel = (p) => relative(root, p).split(sep).join("/");
const excluded = (p) => config.excludeModules.some((m) => p.startsWith(m + "/"));
const skippedKotlin = (p) => config.skipKotlin.some((s) => p === s || p.startsWith(s));

const files = walk(root, []).map(rel).filter((p) => {
  if (excluded(p)) return false;
  if (/^[^/]+\/src\/main\/resources\/lang\/.*\.json$/.test(p)) return true;
  if (/^[^/]+\/src\/main\/kotlin\/.*\.kt$/.test(p)) return !skippedKotlin(p);
  return false;
});

const perToken = {};
const leftovers = {};
let remaining = 0;
let changedFiles = 0;

function convertLine(line, file) {
  return line.replace(TAG, (whole, close, hex) => {
    const token = map[hex.toLowerCase()];
    if (!token) {
      leftovers[file] = (leftovers[file] || new Set()).add("#" + hex.toLowerCase());
      return whole;
    }
    remaining++;
    perToken[token] = (perToken[token] || 0) + 1;
    return `<${close}tc:${token}>`;
  });
}

for (const file of files) {
  const abs = join(root, file);
  const text = readFileSync(abs, "utf8");
  let next;
  if (file.endsWith(".kt")) {
    next = text.split("\n").map((line) => {
      const t = line.trimStart();
      if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return line;
      return convertLine(line, file);
    }).join("\n");
  } else {
    next = convertLine(text, file);
  }
  if (next !== text) {
    changedFiles++;
    if (!check) writeFileSync(abs, next);
  }
}

const total = Object.values(perToken).reduce((a, b) => a + b, 0);
console.log(`${check ? "check" : "converted"}: ${total} tags in ${changedFiles} files`);
for (const [token, n] of Object.entries(perToken).sort((a, b) => b[1] - a[1])) console.log(`  ${token.padEnd(16)} ${n}`);
const left = Object.entries(leftovers);
if (left.length) {
  console.log("unmapped hex colours (left as is):");
  for (const [file, set] of left) console.log(`  ${file}: ${[...set].join(", ")}`);
}
if (check && remaining > 0) {
  console.error(`${remaining} mapped hex colour tags remain – run node tools/theme/convert-colors.mjs`);
  process.exit(1);
}
