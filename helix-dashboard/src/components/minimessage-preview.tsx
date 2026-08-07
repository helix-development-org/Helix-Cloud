import { useMemo } from "react"
import { renderMiniMessage } from "@/lib/minimessage"
import { cn } from "@/lib/utils"

/**
 * Render a MiniMessage string the way it would appear in-game: named/hex colours,
 * gradients and decorations applied, `{placeholders}` marked as dynamic values.
 * Best-effort — click/hover and other functional tags render as plain wrappers.
 */
export function MiniMessagePreview({ value, className }: { value: string; className?: string }) {
  const segments = useMemo(() => renderMiniMessage(value), [value])

  return (
    <div
      className={cn(
        "min-h-[2.25rem] whitespace-pre-wrap break-words rounded-md bg-neutral-900 px-3 py-2 font-mono text-xs leading-relaxed text-neutral-100",
        className,
      )}
    >
      {value.trim() === "" ? (
        <span className="text-neutral-500">Preview appears here…</span>
      ) : (
        segments.map((s, i) =>
          s.text === "\n" ? (
            <br key={i} />
          ) : (
            <span
              key={i}
              className={cn(s.placeholder && "rounded bg-white/10 px-0.5 italic")}
              style={{
                color: s.placeholder ? undefined : s.color,
                fontWeight: s.bold ? 700 : undefined,
                fontStyle: s.italic ? "italic" : undefined,
                textDecoration: [s.underline && "underline", s.strike && "line-through"].filter(Boolean).join(" ") || undefined,
                opacity: s.obfuscated ? 0.55 : undefined,
              }}
            >
              {s.text}
            </span>
          ),
        )
      )}
    </div>
  )
}
