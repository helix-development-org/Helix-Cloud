// Shared MiniMessage knowledge: named colours, tag classification and a small
// renderer that turns a MiniMessage string into styled segments for a preview.
// Kept framework-agnostic so both the CodeMirror language (tokeniser) and the
// React preview component can reuse the same tag tables.

/** Vanilla Minecraft named colours → hex, as MiniMessage resolves them. */
export const NAMED_COLORS: Record<string, string> = {
  black: "#000000",
  dark_blue: "#0000aa",
  dark_green: "#00aa00",
  dark_aqua: "#00aaaa",
  dark_red: "#aa0000",
  dark_purple: "#aa00aa",
  gold: "#ffaa00",
  gray: "#aaaaaa",
  grey: "#aaaaaa",
  dark_gray: "#555555",
  dark_grey: "#555555",
  blue: "#5555ff",
  green: "#55ff55",
  aqua: "#55ffff",
  red: "#ff5555",
  light_purple: "#ff55ff",
  yellow: "#ffff55",
  white: "#ffffff",
}

type Decoration = "bold" | "italic" | "underline" | "strike" | "obfuscated"

/** Decoration tags and their aliases → canonical decoration name. */
const DECORATIONS: Record<string, Decoration> = {
  bold: "bold", b: "bold",
  italic: "italic", i: "italic", em: "italic",
  underlined: "underline", u: "underline",
  strikethrough: "strike", st: "strike",
  obfuscated: "obfuscated", obf: "obfuscated",
}

/** Tags that carry structured arguments rather than a plain colour/decoration. */
const FUNCTION_TAGS = new Set([
  "click", "hover", "insert", "insertion", "key", "keybind", "lang", "translate",
  "font", "gradient", "rainbow", "transition", "score", "selector", "nbt",
  "newline", "br", "color", "colour", "c", "shadow", "pride",
])

export type TagKind = "color" | "decoration" | "reset" | "function" | "unknown"

/** Classify a bare tag name (already lower-cased, without brackets or args). */
export function classifyTag(rawName: string): TagKind {
  const name = rawName.replace(/^[!#]/, "")
  if (rawName.startsWith("#") || /^[0-9a-f]{6}$/.test(name) || /^[0-9a-f]{3}$/.test(name)) return "color"
  if (name in NAMED_COLORS) return "color"
  if (name in DECORATIONS) return "decoration"
  if (name === "reset" || name === "r") return "reset"
  if (FUNCTION_TAGS.has(name)) return "function"
  return "unknown"
}

interface Style {
  color?: string
  bold?: boolean
  italic?: boolean
  underline?: boolean
  strike?: boolean
  obfuscated?: boolean
}

export interface Segment extends Style {
  text: string
  /** True for a `{placeholder}` run — rendered as a dynamic value in the preview. */
  placeholder?: boolean
}

/** Resolve a colour token (`red`, `#ff8800`, `#f80`) to a hex string, or null. */
function resolveColor(token: string): string | null {
  const t = token.toLowerCase()
  if (t in NAMED_COLORS) return NAMED_COLORS[t]
  const hex = t.startsWith("#") ? t.slice(1) : t
  if (/^[0-9a-f]{6}$/.test(hex)) return "#" + hex
  if (/^[0-9a-f]{3}$/.test(hex)) return "#" + hex.split("").map((c) => c + c).join("")
  return null
}

function lerpColor(a: string, b: string, tRatio: number): string {
  const pa = [1, 3, 5].map((i) => parseInt(a.slice(i, i + 2), 16))
  const pb = [1, 3, 5].map((i) => parseInt(b.slice(i, i + 2), 16))
  const mix = pa.map((v, i) => Math.round(v + (pb[i] - v) * tRatio))
  return "#" + mix.map((v) => v.toString(16).padStart(2, "0")).join("")
}

const RAINBOW = ["#ff0000", "#ff8800", "#ffff00", "#00cc00", "#0088ff", "#8800ff"]
function gradientAt(stops: string[], ratio: number): string {
  if (stops.length === 0) return "#ffffff"
  if (stops.length === 1) return stops[0]
  const span = 1 / (stops.length - 1)
  const idx = Math.min(stops.length - 2, Math.floor(ratio / span))
  return lerpColor(stops[idx], stops[idx + 1], (ratio - idx * span) / span)
}

interface Frame extends Style {
  /** Active gradient stops, applied character-by-character over enclosed text. */
  gradient?: string[]
}

/**
 * Parse MiniMessage into flat styled segments for previewing. Best-effort: it
 * understands colours, hex, decorations, reset, gradient/rainbow (interpolated
 * per character) and treats other functional tags (click/hover/…) as neutral
 * wrappers. `{placeholder}` runs are flagged so the UI can mark them dynamic.
 */
export function renderMiniMessage(input: string): Segment[] {
  const segments: Segment[] = []
  const stack: Frame[] = [{}]
  const top = () => stack[stack.length - 1]

  const push = (text: string, placeholder = false) => {
    if (!text) return
    const f = top()
    const style: Style = { bold: f.bold, italic: f.italic, underline: f.underline, strike: f.strike, obfuscated: f.obfuscated }
    if (f.gradient && !placeholder) {
      // Spread a gradient across the individual characters of this run.
      for (let i = 0; i < text.length; i++) {
        const ratio = text.length === 1 ? 0 : i / (text.length - 1)
        segments.push({ text: text[i], ...style, color: gradientAt(f.gradient, ratio) })
      }
      return
    }
    segments.push({ text, ...style, color: f.color, placeholder })
  }

  let i = 0
  let plain = ""
  const flushPlain = () => { push(plain); plain = "" }

  while (i < input.length) {
    const ch = input[i]

    if (ch === "\\" && i + 1 < input.length) {
      const next = input[i + 1]
      if (next === "n") { flushPlain(); segments.push({ text: "\n" }); i += 2; continue }
      if (next === "<" || next === ">" || next === "\\") { plain += next; i += 2; continue }
      plain += ch; i += 1; continue
    }

    if (ch === "{") {
      const end = input.indexOf("}", i)
      if (end !== -1) { flushPlain(); push(input.slice(i, end + 1), true); i = end + 1; continue }
    }

    if (ch === "<") {
      const end = findTagEnd(input, i)
      if (end !== -1) {
        flushPlain()
        applyTag(input.slice(i + 1, end), stack)
        i = end + 1
        continue
      }
    }

    plain += ch
    i += 1
  }
  flushPlain()
  return segments
}

/** Find the `>` that closes the tag started at `open`, skipping quoted args. */
function findTagEnd(input: string, open: number): number {
  let quote: string | null = null
  for (let i = open + 1; i < input.length; i++) {
    const c = input[i]
    if (quote) { if (c === quote) quote = null; continue }
    if (c === "'" || c === '"') { quote = c; continue }
    if (c === "<") return -1 // nested `<` means this wasn't a tag
    if (c === ">") return i
  }
  return -1
}

function applyTag(body: string, stack: Frame[]) {
  const closing = body.startsWith("/")
  const inner = closing ? body.slice(1) : body
  const name = inner.split(":", 1)[0].toLowerCase()
  const kind = classifyTag(name)

  if (closing) {
    if (stack.length > 1) stack.pop()
    return
  }

  if (kind === "reset") { stack.length = 1; return }

  const frame: Frame = { ...stack[stack.length - 1], gradient: undefined }

  if (kind === "color") {
    const resolved = resolveColor(name)
    if (resolved) frame.color = resolved
  } else if (name === "color" || name === "colour" || name === "c") {
    const arg = inner.split(":")[1]
    const resolved = arg ? resolveColor(arg) : null
    if (resolved) frame.color = resolved
  } else if (name in DECORATIONS) {
    frame[DECORATIONS[name]] = true
  } else if (name === "gradient") {
    const stops = inner.split(":").slice(1).map(resolveColor).filter((c): c is string => !!c)
    frame.gradient = stops.length ? stops : ["#ffffff", "#aaaaaa"]
  } else if (name === "rainbow") {
    frame.gradient = RAINBOW
  }
  // Other functional tags (click/hover/insert/…) inherit the parent style.

  stack.push(frame)
}
