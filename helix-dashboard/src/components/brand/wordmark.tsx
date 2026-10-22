import { cn } from "@/lib/utils"
import { NETWORK_NAME } from "@/lib/brand"

/**
 * Network wordmark (DESIGN.md §19): thick cartoon display type, gold fill, 3 px ink outline, 4 px hard shadow.
 * The stroke is painted behind the fill (`paint-order`), so a 6 px stroke shows as a 3 px outline.
 * Minimum height on the web is 24 px.
 */
export function Wordmark({ className, name = NETWORK_NAME, size = 32, mark = false }: { className?: string; name?: string; size?: number; mark?: boolean }) {
  const text = (
    <span
      className={cn("inline-block select-none whitespace-nowrap font-display leading-none text-[var(--gold)]", className)}
      style={{
        fontSize: Math.max(24, size),
        letterSpacing: "0.03em",
        WebkitTextStroke: "6px var(--ink)",
        paintOrder: "stroke fill",
        textShadow: "0 4px 0 var(--ink)",
        padding: "3px 3px 7px",
      }}
      aria-label={name}
    >
      {name}
    </span>
  )
  if (!mark) return text
  return (
    <span className="inline-flex items-center gap-2">
      <BrandMark size={Math.round(Math.max(24, size) * 1.35)} />
      {text}
    </span>
  )
}

/**
 * Network brand mark (DESIGN.md §19): the crown seal, drawn as pixel art and therefore scaled without smoothing.
 * Uses the 64 px icon up to 64 px and the 256 px logo above that.
 */
export function BrandMark({ className, size = 40 }: { className?: string; size?: number }) {
  return (
    <img
      src={size > 64 ? "./brand/logo-256.png" : "./brand/icon-64.png"}
      width={size}
      height={size}
      alt=""
      aria-hidden
      draggable={false}
      className={cn("select-none", className)}
      style={{ imageRendering: "pixelated" }}
    />
  )
}
