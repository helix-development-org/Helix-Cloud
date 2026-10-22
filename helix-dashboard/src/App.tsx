import { useEffect, useState } from "react"
import { api, setToken, setUnauthorizedHandler, type Identity, type Overview, type PanelInfo } from "@/lib/api"
import { useViewReveal } from "@/lib/anim"
import { AppShell } from "@/components/app-shell"
import { Toaster } from "@/components/ui/sonner"
import { Wordmark } from "@/components/brand/wordmark"
import { NETWORK_NAME } from "@/lib/brand"
import { LoginView } from "@/views/login"
import { OverviewView } from "@/views/overview"
import { ServicesView } from "@/views/services"
import { TasksView } from "@/views/tasks"
import { PlayersView } from "@/views/players"
import { ProxyPageView } from "@/views/proxy"
import { EventsView, LogsView, AuditView, AuditLogView } from "@/views/feeds"
import { AddonsView } from "@/views/addons"
import { SchedulesView } from "@/views/schedules"
import { BackupsView } from "@/views/backups"
import { FilesView } from "@/views/files"
import { TranslationsView } from "@/views/translations"
import { SettingsView } from "@/views/settings"
import { AddonPanelView } from "@/views/addon-panel"
import { AccessView } from "@/views/access"
import { AccessProvider } from "@/lib/access"
import { IngameMenuView } from "@/views/ingame-menu"
import { isIngame, onModeChange, reportExpired, requestHud, requestMouse, takeTicketFromUrl } from "@/lib/ingame"

const TITLES: Record<string, string> = {
  overview: "Overview", tasks: "Tasks", services: "Services", players: "Players", proxy: "Proxy",
  events: "Events", logs: "Konsole", audit: "Logs", "audit-log": "Audit", addons: "Addons",
  schedules: "Schedules", backups: "Backups", files: "Files", translations: "Translations", settings: "Settings",
  access: "Zugriff",
}

export function App() {
  const [identity, setIdentity] = useState<Identity | null>(null)
  const [booting, setBooting] = useState(true)
  const [panels, setPanels] = useState<PanelInfo[]>([])
  const [version, setVersion] = useState("")
  const [view, setView] = useState("overview")
  // Deep link (`…/#view=panel:testguide`, e.g. from the in-game /befehle): opened right after sign-in when allowed
  const [wanted] = useState(() => new URLSearchParams(location.hash.replace(/^#/, "")).get("view"))
  // In-game (Helix Admin mod): quick menu first, the full dashboard on request
  const [ingame] = useState(isIngame)
  const [fullDashboard, setFullDashboard] = useState(false)
  // Back in the walkable HUD mode (Escape in the mod) → quick menu again; the full dashboard needs the mouse
  useEffect(() => (ingame ? onModeChange((mode) => { if (mode !== "mouse") setFullDashboard(false) }) : undefined), [ingame])
  const openDashboard = () => { setFullDashboard(true); requestMouse() }
  const backToMenu = () => { setFullDashboard(false); requestHud() }

  const establish = async (tok: string) => {
    setToken(tok)
    const me = await api<Identity>("/auth/me")
    const [pnls, ov] = await Promise.all([
      api<PanelInfo[]>("/panels").catch(() => [] as PanelInfo[]),
      api<Overview>("/platform/overview").catch(() => null),
    ])
    setPanels(pnls)
    setVersion(ov?.version ?? "")
    setIdentity(me)
    const first = me.admin ? "overview" : me.views[0] ?? (pnls[0] ? "panel:" + pnls[0].id : "overview")
    setView(wanted ?? first)
  }

  useEffect(() => {
    setUnauthorizedHandler(() => { setToken(""); setIdentity(null) })
    const ticket = takeTicketFromUrl()
    if (ticket) {
      // one-time login ticket from the in-game panel: exchange it for a session
      api<{ token: string }>("/auth/ticket", { method: "POST", body: { ticket } })
        .then((res) => establish(res.token))
        .catch(() => setToken(""))
        .finally(() => setBooting(false))
      return
    }
    const stored = localStorage.getItem("helix.token")
    if (stored) establish(stored).catch(() => setToken("")).finally(() => setBooting(false))
    else setBooting(false)
  }, [])

  const logout = async () => {
    try { await api("/auth/logout", { method: "POST" }) } catch { /* ignore */ }
    setToken("")
    setIdentity(null)
  }

  if (booting) return ingame ? null : (
    <div className="theme-wood kl-planks grid min-h-screen place-items-center text-foreground">
      <div className="flex flex-col items-center gap-3"><Wordmark size={40} /><span className="font-bold text-muted-foreground">Loading…</span></div>
    </div>
  )
  if (!identity && ingame) return <IngameSignedOut />
  if (!identity) return (<><LoginView establish={establish} /><Toaster /></>)
  if (ingame && !fullDashboard) {
    document.documentElement.classList.add("ingame-transparent")
    return (<><IngameMenuView identity={identity} onOpenDashboard={openDashboard} /><Toaster /></>)
  }
  document.documentElement.classList.remove("ingame-transparent")

  const allowed = (v: string) => v.startsWith("panel:") ? panels.some((p) => p.id === v.slice(6)) : identity.admin || identity.views.includes(v)
  const current = allowed(view) ? view : (identity.admin ? "overview" : identity.views[0] ?? (panels[0] ? "panel:" + panels[0].id : "overview"))
  const title = current.startsWith("panel:") ? panels.find((p) => p.id === current.slice(6))?.title ?? "Extension" : TITLES[current] ?? NETWORK_NAME

  return (
    <AccessProvider identity={identity}>
      <AppShell identity={identity} panels={panels} view={current} title={title} version={version}
        onNavigate={setView} onLogout={logout}>
        <ViewTransition viewKey={current}>
        {current === "overview" && <OverviewView />}
        {current === "tasks" && <TasksView />}
        {current === "services" && <ServicesView />}
        {current === "players" && <PlayersView />}
        {current === "proxy" && <ProxyPageView />}
        {current === "events" && <EventsView />}
        {current === "logs" && <LogsView />}
        {current === "audit" && <AuditView />}
        {current === "audit-log" && <AuditLogView />}
        {current === "addons" && <AddonsView />}
        {current === "schedules" && <SchedulesView />}
        {current === "backups" && <BackupsView />}
        {current === "files" && <FilesView />}
        {current === "translations" && <TranslationsView />}
        {current === "settings" && <SettingsView identity={identity} version={version} />}
        {current === "access" && <AccessView />}
        {current.startsWith("panel:") && <AddonPanelView panelId={current.slice(6)} />}
        </ViewTransition>
      </AppShell>
      {ingame && (
        <button onClick={backToMenu}
          className="kl-raised kl-press fixed bottom-4 right-4 z-50 rounded-full border-[3px] border-border bg-[var(--gold)] px-4 pt-2 pb-2.5 font-display text-sm text-[var(--ink)] [--bevel-hi:var(--gold-hi)] [--bevel-lo:var(--gold-lo)]">
          ← In-game menu
        </button>
      )}
      <Toaster />
    </AccessProvider>
  )
}

/**
 * In-game without a session (ticket rejected or session expired): no login form – the mod drops the page and
 * the next Right Shift signs in with a fresh ticket.
 */
function IngameSignedOut() {
  useEffect(() => { reportExpired() }, [])
  return (
    <div className="theme-wood kl-panel fixed left-4 top-4 w-[400px] rounded-[var(--radius-panel)] border-[3px] border-border bg-card p-4 pb-5 text-sm font-bold text-foreground">
      Session expired – press Right Shift again.
    </div>
  )
}

/** Staggered fade/rise reveal of the active view, replayed on every navigation. */
function ViewTransition({ viewKey, children }: { viewKey: string; children: React.ReactNode }) {
  const ref = useViewReveal(viewKey)
  return <div ref={ref} key={viewKey}>{children}</div>
}
