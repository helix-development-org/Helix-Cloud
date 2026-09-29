/**
 * In-game mode: the dashboard runs inside the Helix Admin client mod (embedded Chromium). The mod opens
 * `…/#ticket=<one-time ticket>&ingame=1`; the ticket is exchanged for a session and the compact in-game menu
 * is shown. In-game actions go to the mod through its console bridge ([mc]), which forwards them to the
 * player's server.
 */

declare global {
  interface Window {
    /** Called by the mod with the answer to a bridge request (see [mc]). */
    __helixReply?: (id: number, ok: boolean, payload: string) => void
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
  return sessionStorage.getItem(INGAME_KEY) === "1"
}

const pending = new Map<number, { resolve: (payload: string) => void; reject: (e: Error) => void }>()
let nextId = 1

window.__helixReply = (id, ok, payload) => {
  const call = pending.get(id)
  if (!call) return
  pending.delete(id)
  if (ok) call.resolve(payload)
  else call.reject(new Error(payload))
}

/**
 * Calls an in-game op through the mod (e.g. `self.noclip`, `player.goto {name}`); rejects with the
 * server's error message (missing permission, player offline, …). The request goes to the mod as a
 * console message (`helix-bridge:{id, op, args}`), the mod answers through `window.__helixReply`.
 */
export function mc<T = Record<string, unknown>>(op: string, args: Record<string, unknown> = {}): Promise<T> {
  if (!isIngame()) return Promise.reject(new Error("only available in-game (Helix Admin mod)"))
  const id = nextId++
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id)
      reject(new Error("no answer from the Helix Admin mod"))
    }, 15000)
    pending.set(id, {
      resolve: (payload) => { clearTimeout(timer); resolve((payload ? JSON.parse(payload) : {}) as T) },
      reject: (e) => { clearTimeout(timer); reject(e) },
    })
    console.log("helix-bridge:" + JSON.stringify({ id, op, args }))
  })
}

/** Closes the in-game overlay. */
export function closeOverlay() {
  mc("close").catch(() => {})
}

/** How the mod currently shows the panel: walkable HUD menu, mouse/typing mode, or hidden. */
export type IngameMode = "hud" | "mouse" | "closed"

/** Navigation keys the mod forwards while the HUD menu is shown (arrows, Enter, Backspace by default). */
export type NavKey = "up" | "down" | "left" | "right" | "select" | "back"

/** Subscribes to the mod's mode changes (`helix-mode` events); returns the unsubscribe function. */
export function onModeChange(listener: (mode: IngameMode) => void): () => void {
  const handler = (e: Event) => listener((e as CustomEvent<IngameMode>).detail)
  window.addEventListener("helix-mode", handler)
  return () => window.removeEventListener("helix-mode", handler)
}

/** Subscribes to navigation keys from the mod (`helix-nav` events); returns the unsubscribe function. */
export function onNavKey(listener: (key: NavKey) => void): () => void {
  const handler = (e: Event) => listener((e as CustomEvent<NavKey>).detail)
  window.addEventListener("helix-nav", handler)
  return () => window.removeEventListener("helix-nav", handler)
}

/** Asks the mod for mouse/typing mode (input fields, full dashboard). */
export function requestMouse() {
  mc("interact").catch(() => {})
}

/** Asks the mod to go back to the walkable HUD menu. */
export function requestHud() {
  mc("hud").catch(() => {})
}

/** Tells the mod the page has no session (ticket failed or expired): it hides the panel and signs in anew next time. */
export function reportExpired() {
  mc("expired").catch(() => {})
}
