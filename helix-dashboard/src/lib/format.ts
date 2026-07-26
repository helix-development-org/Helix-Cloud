/** Human "time ago" from an epoch-millis timestamp. */
export function ago(ms?: number | null): string {
  if (!ms) return "—"
  const s = Math.floor((Date.now() - ms) / 1000)
  if (s < 60) return s + "s ago"
  if (s < 3600) return Math.floor(s / 60) + "m ago"
  if (s < 86400) return Math.floor(s / 3600) + "h ago"
  return Math.floor(s / 86400) + "d ago"
}

/** Human uptime from a start timestamp. */
export function uptime(ms?: number | null): string {
  if (!ms) return "—"
  const s = Math.floor((Date.now() - ms) / 1000)
  if (s < 60) return s + "s"
  if (s < 3600) return Math.floor(s / 60) + "m " + (s % 60) + "s"
  return Math.floor(s / 3600) + "h " + Math.floor((s % 3600) / 60) + "m"
}

/** Human duration from a millisecond span (e.g. an uptime counter). */
export function duration(ms?: number | null): string {
  if (ms == null || ms < 0) return "—"
  const s = Math.floor(ms / 1000)
  if (s < 60) return s + "s"
  const m = Math.floor(s / 60)
  if (m < 60) return m + "m " + (s % 60) + "s"
  const h = Math.floor(m / 60)
  if (h < 24) return h + "h " + (m % 60) + "m"
  const d = Math.floor(h / 24)
  return d + "d " + (h % 24) + "h"
}

/** Maps a service/addon state to a badge variant. */
export function stateVariant(state: string): "default" | "success" | "warning" | "destructive" | "secondary" {
  switch (state.toUpperCase()) {
    case "RUNNING":
    case "ENABLED":
      return "success"
    case "STARTING":
    case "STOPPING":
      return "warning"
    case "FAILED":
    case "CRASHED":
      return "destructive"
    default:
      return "secondary"
  }
}
