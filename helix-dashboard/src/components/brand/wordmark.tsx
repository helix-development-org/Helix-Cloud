import { cn } from "@/lib/utils"
import { NETWORK_NAME } from "@/lib/brand"

/**
 * Network wordmark (DESIGN.md §19): thick cartoon display type, gold fill, 3 px ink outline, 4 px hard shadow.
 * The stroke is painted behind the fill (`paint-order`), so a 6 px stroke shows as a 3 px outline.
 * Minimum height on the web is 24 px.
 */
export function Wordmark({ className, name = NETWORK_NAME, size = 32 }: { className?: string; name?: string; size?: number }) {
  return (
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
}
