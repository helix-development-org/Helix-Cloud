import { useEffect, useMemo, useState } from "react"
import { renderMiniMessage, type Segment } from "@/lib/minimessage"
import { cn } from "@/lib/utils"

// Glyphs cycled through for <obfuscated> ("magic") text, matching the look of
// the in-game scramble. Whitespace is preserved so word shapes stay stable.
const OBF_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789?!@#$%&*"

function scramble(text: string): string {
  let out = ""
  for (const ch of text) out += ch === " " ? " " : OBF_CHARS[(Math.random() * OBF_CHARS.length) | 0]
  return out
}

/**
 * Render a MiniMessage string the way it appears in the Minecraft client:
 * the "Minecraft" font with its hard offset drop shadow over the tiled,
 * darkened dirt of the in-game menu background. Colours, gradients and
 * decorations are applied; `<obfuscated>` text animates; `{placeholders}` are
 * rendered in place (slightly dimmed to flag them as dynamic values).
 */
export function MiniMessagePreview({ value, className }: { value: string; className?: string }) {
  const segments = useMemo(() => renderMiniMessage(value), [value])
  const hasObfuscated = useMemo(() => segments.some((s) => s.obfuscated), [segments])
  const [, setTick] = useState(0)

  // Re-render a few times a second only while obfuscated text is present, and
  // never when the viewer prefers reduced motion.
  useEffect(() => {
    if (!hasObfuscated) return
    if (window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) return
    const id = window.setInterval(() => setTick((t) => (t + 1) % 1_000_000), 80)
    return () => window.clearInterval(id)
  }, [hasObfuscated])

  return (
    <div className={cn("mc-preview overflow-hidden rounded-md", className)}>
      <div className="mc-preview-inner min-h-[2.75rem] whitespace-pre-wrap break-words px-3 py-2">
        {value.trim() === "" ? (
          <span style={{ opacity: 0.5 }}>Vorschau …</span>
        ) : (
          segments.map((s, i) => (s.text === "\n" ? <br key={i} /> : <PreviewSpan key={i} seg={s} />))
        )}
      </div>
    </div>
  )
}

function PreviewSpan({ seg }: { seg: Segment }) {
  const text = seg.obfuscated ? scramble(seg.text) : seg.text
  return (
    <span
      style={{
        color: seg.color,
        fontWeight: seg.bold ? 700 : undefined,
        fontStyle: seg.italic ? "italic" : undefined,
        textDecoration:
          [seg.underline && "underline", seg.strike && "line-through"].filter(Boolean).join(" ") || undefined,
        opacity: seg.placeholder ? 0.85 : undefined,
      }}
    >
      {text}
    </span>
  )
}
