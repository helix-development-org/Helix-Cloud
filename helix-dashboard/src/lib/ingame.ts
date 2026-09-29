/**
 * In-game mode: the dashboard runs inside the Helix Admin client mod (embedded Chromium). The mod opens
 * `…/#ticket=<one-time ticket>&ingame=1`; the ticket is exchanged for a session and the compact in-game menu
 * is shown. In-game actions go to the mod through its JavaScript bridge (`window.cefQuery`), which forwards
 * them to the player's server.
 */

declare global {
  interface Window {
    cefQuery?: (query: { request: string; persistent?: boolean; onSuccess: (response: string) => void; onFailure: (code: number, message: string) => void }) => number
  }
}

const INGAME_KEY = "helix.ingame"

/** Reads (and strips) the login ticket from the URL fragment; remembers in-game mode for this tab. */
export function takeTicketFromUrl(): string | null {
  const params = new URLSearchParams(location.hash.replace(/^#/, ""))
  const ticket = params.get("ticket")
  if (params.get("ingame") === "1") sessionStorage.setItem(INGAME_KEY, "1")
  if (ticket || params.has("ingame")) history.replaceState(null, "", location.pathname + location.search)
  return ticket
}

/**
 * Whether the dashboard runs inside the client mod. Also reads the URL fragment directly: this runs on the
 * first render, before [takeTicketFromUrl] has stored the flag.
 */
export function isIngame(): boolean {
  if (new URLSearchParams(location.hash.replace(/^#/, "")).get("ingame") === "1") return true
  return sessionStorage.getItem(INGAME_KEY) === "1" || typeof window.cefQuery === "function"
}

/** Waits briefly for the mod's bridge (`window.cefQuery`), which can appear after the page script started. */
function bridge(timeoutMs = 3000): Promise<NonNullable<Window["cefQuery"]>> {
  return new Promise((resolve, reject) => {
    const started = Date.now()
    const check = () => {
      if (typeof window.cefQuery === "function") resolve(window.cefQuery)
      else if (Date.now() - started > timeoutMs) reject(new Error("only available in-game (Helix Admin mod)"))
      else setTimeout(check, 100)
    }
    check()
  })
}

/**
 * Calls an in-game op through the mod (e.g. `self.noclip`, `player.goto {name}`); rejects with the
 * server's error message (missing permission, player offline, …).
 */
export function mc<T = Record<string, unknown>>(op: string, args: Record<string, unknown> = {}): Promise<T> {
  return bridge().then((cefQuery) => new Promise<T>((resolve, reject) => {
    cefQuery({
      request: JSON.stringify({ op, args }),
      onSuccess: (response) => resolve((response ? JSON.parse(response) : {}) as T),
      onFailure: (_code, message) => reject(new Error(message)),
    })
  }))
}

/** Closes the in-game overlay. */
export function closeOverlay() {
  mc("close").catch(() => {})
}
