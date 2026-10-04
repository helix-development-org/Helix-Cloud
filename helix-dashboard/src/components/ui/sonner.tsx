import { useEffect, useState } from "react"
import { Toaster as Sonner } from "sonner"

/** App-wide toast host: parchment/wood note with an ink outline and a hard drop shadow (§4). */
export function Toaster() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains("dark"))
  useEffect(() => {
    const observer = new MutationObserver(() => setDark(document.documentElement.classList.contains("dark")))
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] })
    return () => observer.disconnect()
  }, [])
  return (
    <Sonner
      theme={dark ? "dark" : "light"}
      position="bottom-center"
      toastOptions={{
        style: {
          background: "var(--popover)",
          color: "var(--popover-foreground)",
          border: "3px solid var(--border)",
          borderRadius: "14px",
          boxShadow: "inset 0 4px 0 var(--card-hi), inset 0 -6px 0 var(--card-lo), 0 4px 0 var(--border)",
          fontFamily: "var(--font-sans), ui-rounded, system-ui, sans-serif",
          fontWeight: 700,
        },
      }}
    />
  )
}
