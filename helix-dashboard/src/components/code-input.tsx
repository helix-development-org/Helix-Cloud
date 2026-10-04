import { useRef } from "react"
import { cn } from "@/lib/utils"

/**
 * One-time code entry as separate digit fields (DESIGN.md §17). Typing advances, Backspace steps back, arrow keys
 * move, and pasting the whole code fills every field. The value is the plain digit string.
 */
export function CodeInput({ value, onChange, onSubmit, length = 6, disabled, autoFocus, idPrefix = "code" }: {
  value: string
  onChange: (value: string) => void
  onSubmit?: () => void
  length?: number
  disabled?: boolean
  autoFocus?: boolean
  idPrefix?: string
}) {
  const refs = useRef<(HTMLInputElement | null)[]>([])
  const digits = Array.from({ length }, (_, i) => value[i] ?? "")
  const focus = (i: number) => refs.current[Math.max(0, Math.min(length - 1, i))]?.focus()

  // The value stays contiguous (no holes), so field i never sits behind an empty one.
  const setAt = (i: number, chars: string) => {
    const at = Math.min(i, value.length)
    const next = (value.slice(0, at) + chars + value.slice(at + chars.length)).slice(0, length)
    onChange(next)
    focus(at + chars.length)
  }

  return (
    <div className="flex justify-center gap-2" role="group" aria-label="Login code">
      {digits.map((d, i) => (
        <input
          key={i}
          id={i === 0 ? idPrefix : undefined}
          ref={(el) => { refs.current[i] = el }}
          value={d}
          disabled={disabled}
          autoFocus={autoFocus && i === 0}
          inputMode="numeric"
          autoComplete={i === 0 ? "one-time-code" : "off"}
          aria-label={`Digit ${i + 1}`}
          maxLength={length}
          onFocus={(e) => {
            if (i > value.length) focus(value.length)
            else e.currentTarget.select()
          }}
          onChange={(e) => {
            const chars = e.target.value.replace(/\D/g, "")
            if (!chars) return
            // A single typed digit replaces this field; a longer value (autofill / paste) spreads over the following ones.
            setAt(i, chars.length > 1 && d && chars.startsWith(d) ? chars.slice(1) : chars)
          }}
          onPaste={(e) => {
            const chars = e.clipboardData.getData("text").replace(/\D/g, "")
            if (!chars) return
            e.preventDefault()
            setAt(i, chars)
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              onSubmit?.()
            } else if (e.key === "Backspace") {
              e.preventDefault()
              if (value[i]) {
                onChange(value.slice(0, i) + value.slice(i + 1))
              } else if (i > 0) {
                onChange(value.slice(0, i - 1) + value.slice(i))
                focus(i - 1)
              }
            } else if (e.key === "ArrowLeft") {
              e.preventDefault()
              focus(i - 1)
            } else if (e.key === "ArrowRight") {
              e.preventDefault()
              focus(i + 1)
            }
          }}
          className={cn(
            "kl-field h-14 w-11 rounded-[12px] border border-border text-center font-mono text-2xl font-bold text-foreground caret-[var(--ribbon)]",
            "transition-[box-shadow,transform] duration-150 ease-out focus:outline-none focus:ring-[3px] focus:ring-ring disabled:opacity-55",
            d && "bg-[var(--parchment-hi)]",
          )}
        />
      ))}
    </div>
  )
}
