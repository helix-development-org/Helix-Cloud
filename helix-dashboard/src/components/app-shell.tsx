import { useState, type ReactNode } from "react"
import {
  Archive, Blocks, Clock, LayoutDashboard, ListChecks, LogOut, MessageSquare, Moon, Network,
  Puzzle, ScrollText, Server, Settings, ShieldCheck, Sun, Users, Zap,
} from "lucide-react"
import type { Identity, PanelInfo } from "@/lib/api"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"

interface NavItem {
  id: string
  label: string
  icon: typeof LayoutDashboard
  group: string
}

const NAV: NavItem[] = [
  { id: "overview", label: "Overview", icon: LayoutDashboard, group: "Platform" },
  { id: "tasks", label: "Tasks", icon: ListChecks, group: "Platform" },
  { id: "services", label: "Services", icon: Server, group: "Platform" },
  { id: "players", label: "Players", icon: Users, group: "Platform" },
  { id: "proxy", label: "Proxy", icon: Network, group: "Platform" },
  { id: "events", label: "Events", icon: Zap, group: "Observability" },
  { id: "logs", label: "Logs", icon: ScrollText, group: "Observability" },
  { id: "audit", label: "Audit", icon: ShieldCheck, group: "Observability" },
  { id: "addons", label: "Addons", icon: Blocks, group: "System" },
  { id: "schedules", label: "Schedules", icon: Clock, group: "System" },
  { id: "backups", label: "Backups", icon: Archive, group: "System" },
  { id: "messages", label: "Messages", icon: MessageSquare, group: "System" },
  { id: "settings", label: "Settings", icon: Settings, group: "System" },
]

/** Sidebar + topbar chrome around the active view. */
export function AppShell(props: {
  identity: Identity
  panels: PanelInfo[]
  view: string
  title: string
  version: string
  onNavigate: (view: string) => void
  onLogout: () => void
  children: ReactNode
}) {
  const { identity, panels } = props
  const [dark, setDark] = useState(document.documentElement.classList.contains("dark"))
  const allowed = (id: string) => identity.admin || identity.views.includes(id)
  const groups = ["Platform", "Observability", "System"]

  const toggleTheme = () => {
    const next = !dark
    setDark(next)
    document.documentElement.classList.toggle("dark", next)
  }

  return (
    <div className="flex h-screen bg-background text-foreground">
      <aside className="flex w-60 flex-col border-r bg-sidebar">
        <div className="flex items-center gap-2.5 px-5 py-4">
          <div className="grid size-8 place-items-center rounded-lg bg-primary font-bold text-primary-foreground">H</div>
          <div className="font-semibold">Helix <span className="text-xs font-normal text-muted-foreground">Cloud</span></div>
        </div>
        <nav className="flex-1 overflow-y-auto px-3 pb-4">
          {groups.map((group) => {
            const items = NAV.filter((n) => n.group === group && allowed(n.id))
            if (!items.length) return null
            return (
              <div key={group} className="mb-3">
                <div className="px-2 py-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/70">{group}</div>
                {items.map((n) => (
                  <NavButton key={n.id} active={props.view === n.id} icon={<n.icon className="size-4" />}
                    label={n.label} onClick={() => props.onNavigate(n.id)} />
                ))}
              </div>
            )
          })}
          {panels.length > 0 && (
            <div className="mb-3">
              <div className="px-2 py-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/70">Extensions</div>
              {panels.map((p) => (
                <NavButton key={p.id} active={props.view === "panel:" + p.id} icon={<Puzzle className="size-4" />}
                  label={p.title} onClick={() => props.onNavigate("panel:" + p.id)} />
              ))}
            </div>
          )}
        </nav>
        <div className="border-t p-3">
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="sm" className="flex-1 justify-start" onClick={toggleTheme}>
              {dark ? <Moon className="size-4" /> : <Sun className="size-4" />} Theme
            </Button>
            <Button variant="ghost" size="icon" onClick={props.onLogout} title="Sign out"><LogOut className="size-4" /></Button>
          </div>
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between border-b px-6 py-3.5">
          <h1 className="text-base font-semibold">{props.title}</h1>
          <div className="flex items-center gap-3 text-sm text-muted-foreground">
            <span className="rounded-md bg-secondary px-2 py-1 text-xs">{identity.admin ? "admin" : identity.name}</span>
            <span className="rounded-md bg-secondary px-2 py-1 text-xs">v{props.version}</span>
          </div>
        </header>
        <main className="flex-1 overflow-y-auto p-6">{props.children}</main>
      </div>
    </div>
  )
}

function NavButton({ active, icon, label, onClick }: { active: boolean; icon: ReactNode; label: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "flex w-full items-center gap-2.5 rounded-md px-2 py-2 text-sm transition-colors",
        active ? "bg-sidebar-accent font-medium text-sidebar-accent-foreground" : "text-muted-foreground hover:bg-sidebar-accent/60 hover:text-foreground",
      )}
    >
      {icon}
      <span className="truncate">{label}</span>
    </button>
  )
}
