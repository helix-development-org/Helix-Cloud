import { useEffect, useState } from "react"
import { api, setToken, setUnauthorizedHandler, type Identity, type Overview, type PanelInfo } from "@/lib/api"
import { useViewReveal } from "@/lib/anim"
import { AppShell } from "@/components/app-shell"
import { Toaster } from "@/components/ui/sonner"
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

const TITLES: Record<string, string> = {
  overview: "Overview", tasks: "Tasks", services: "Services", players: "Players", proxy: "Proxy",
  events: "Events", logs: "Konsole", audit: "Logs", "audit-log": "Audit", addons: "Addons",
  schedules: "Schedules", backups: "Backups", files: "Files", translations: "Translations", settings: "Settings",
}

export function App() {
  const [identity, setIdentity] = useState<Identity | null>(null)
  const [booting, setBooting] = useState(true)
  const [panels, setPanels] = useState<PanelInfo[]>([])
  const [version, setVersion] = useState("")
  const [view, setView] = useState("overview")

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
    setView(first)
  }

  useEffect(() => {
    setUnauthorizedHandler(() => { setToken(""); setIdentity(null) })
    const stored = localStorage.getItem("helix.token")
    if (stored) establish(stored).catch(() => setToken("")).finally(() => setBooting(false))
    else setBooting(false)
  }, [])

  const logout = async () => {
    try { await api("/auth/logout", { method: "POST" }) } catch { /* ignore */ }
    setToken("")
    setIdentity(null)
  }

  if (booting) return <div className="grid min-h-screen place-items-center bg-background text-muted-foreground">Loading…</div>
  if (!identity) return (<><LoginView establish={establish} /><Toaster /></>)

  const allowed = (v: string) => v.startsWith("panel:") ? panels.some((p) => p.id === v.slice(6)) : identity.admin || identity.views.includes(v)
  const current = allowed(view) ? view : (identity.admin ? "overview" : identity.views[0] ?? (panels[0] ? "panel:" + panels[0].id : "overview"))
  const title = current.startsWith("panel:") ? panels.find((p) => p.id === current.slice(6))?.title ?? "Extension" : TITLES[current] ?? "Helix"

  return (
    <>
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
        {current.startsWith("panel:") && <AddonPanelView panelId={current.slice(6)} />}
        </ViewTransition>
      </AppShell>
      <Toaster />
    </>
  )
}

/** Staggered fade/rise reveal of the active view, replayed on every navigation. */
function ViewTransition({ viewKey, children }: { viewKey: string; children: React.ReactNode }) {
  const ref = useViewReveal(viewKey)
  return <div ref={ref} key={viewKey}>{children}</div>
}
