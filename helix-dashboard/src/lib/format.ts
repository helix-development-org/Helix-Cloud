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
