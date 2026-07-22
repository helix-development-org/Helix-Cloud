const API = "/api/v1"

/** Current bearer token (admin token or MC session token). */
export let token = localStorage.getItem("helix.token") || ""

/** Called when a request returns 401, so the app can show the login screen. */
export let onUnauthorized: () => void = () => {}

/** Registers the 401 handler. */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

/** Sets (or clears) the bearer token and persists it. */
export function setToken(value: string) {
  token = value
  if (value) localStorage.setItem("helix.token", value)
  else localStorage.removeItem("helix.token")
}

/** Options for {@link api}. */
export interface ApiOptions {
  method?: string
  body?: unknown
}

/**
 * Calls the control API with the bearer token, parsing JSON and surfacing
 * server error messages. Triggers the 401 handler on unauthorized.
 */
export async function api<T = unknown>(path: string, opts: ApiOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Authorization: "Bearer " + token }
  if (opts.body !== undefined) headers["Content-Type"] = "application/json"
  const res = await fetch(API + path, {
    method: opts.method || "GET",
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })
  if (res.status === 401) {
    onUnauthorized()
    throw new Error("unauthorized")
  }
  const text = await res.text()
  const parsed = text ? JSON.parse(text) : null
  if (!res.ok) throw new Error(parsed?.message || "HTTP " + res.status)
  return parsed as T
}

export interface Identity {
  name: string
  admin: boolean
  views: string[]
}
export interface Overview {
  version: string
  taskCount: number
  servicesRunning: number
  servicesTotal: number
  onlinePlayers: number
  maxPlayers: number
}
export interface MetricSample {
  epochMs: number
  onlinePlayers: number
  maxPlayers: number
  servicesRunning: number
  servicesTotal: number
  avgTps: number | null
  avgApiMs: number | null
}
export interface ApiStats {
  avgMs: number
  p95Ms: number
  requestsPerMinute: number
  errorRate: number
  totalRequests: number
}
export interface ServiceInfo {
  id: string
  taskName: string
  environment: string
  executor: string
  state: string
  port: number
  static: boolean
  onlinePlayers: number
  maxPlayers: number
  startedAtEpochMs: number | null
}
export interface TaskDefinition {
  name: string
  environment: { name: string; proxy: boolean }
  executor: string
  [key: string]: unknown
}
export interface OnlinePlayer {
  name: string
  uuid: string | null
  proxyServiceId: string
  joinedAtEpochMs: number
}
export interface EventEntry {
  epochMs: number
  category: string
  message: string
  level: string
}
export interface AuditEntry {
  epochMs: number
  category: string
  actor: string
  summary: string
  outcome: string
}
export interface AddonInfo {
  manifest: { id: string; name: string; version: string }
  state: string
}
export interface PanelInfo {
  id: string
  title: string
  icon: string
}
export interface ScheduledJob {
  id: string
  action: string
  arguments: string[]
  everyMinutes: number
  dailyAt: string | null
  enabled: boolean
}
export interface ActionResult {
  success: boolean
  lines: string[]
}
export interface ProxyView {
  maintenance: boolean
  proxies: { id: string; state: string; port: number; onlinePlayers: number; maxPlayers: number }[]
  backends: { id: string; task: string; state: string; host: string; port: number; onlinePlayers: number }[]
}
