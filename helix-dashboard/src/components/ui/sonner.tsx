import { Toaster as Sonner } from "sonner"

/** App-wide toast host, themed to match the dashboard. */
export function Toaster() {
  return (
    <Sonner
      theme="dark"
      position="bottom-center"
      toastOptions={{
        style: {
          background: "var(--popover)",
          color: "var(--popover-foreground)",
          border: "1px solid var(--border)",
        },
      }}
    />
  )
}
