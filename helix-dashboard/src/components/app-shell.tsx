import { useEffect, useRef, useState, type ReactNode } from "react"
import { animate } from "animejs"
import {
  Archive, Blocks, Bug, Clock, ClipboardList, FolderTree, KeyRound, Languages, LayoutDashboard, ListChecks, LogOut, Moon,
  Network, Puzzle, ScrollText, Server, Settings, ShieldCheck, SquareTerminal, Sun, Users, Zap,
} from "lucide-react"
import type { Identity, PanelInfo } from "@/lib/api"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Wordmark } from "@/components/brand/wordmark"
import { applyTheme } from "@/lib/theme"
import { isIntegratedPanel } from "@/lib/integrated"

interface NavItem {
  id: string
  label: string
  icon: typeof LayoutDashboard
  group: string
}

const NAV: NavItem[] = [
  // Integrated addon views (feedback addon): visible when the addon registered the panel for this user.
  { id: "testguide", label: "Befehle", icon: SquareTerminal, group: "Testphase" },
  { id: "bugreport", label: "Bug melden", icon: Bug, group: "Testphase" },
  { id: "overview", label: "Overview", icon: LayoutDashboard, group: "Platform" },
  { id: "tasks", label: "Tasks", icon: ListChecks, group: "Platform" },
  { id: "services", label: "Services", icon: Server, group: "Platform" },
  { id: "players", label: "Players", icon: Users, group: "Platform" },
  { id: "proxy", label: "Proxy", icon: Network, group: "Platform" },
  { id: "events", label: "Events", icon: Zap, group: "Observability" },
  { id: "logs", label: "Launcher-Logs", icon: ScrollText, group: "Observability" },
  { id: "audit", label: "Logs", icon: ClipboardList, group: "Observability" },
  { id: "audit-log", label: "Audit", icon: ShieldCheck, group: "Observability" },
  { id: "addons", label: "Addons", icon: Blocks, group: "System" },
  { id: "schedules", label: "Schedules", icon: Clock, group: "System" },
  { id: "backups", label: "Backups", icon: Archive, group: "System" },
  { id: "files", label: "Files", icon: FolderTree, group: "System" },
  { id: "translations", label: "Translations", icon: Languages, group: "System" },
  { id: "settings", label: "Settings", icon: Settings, group: "System" },
  { id: "access", label: "Zugriff", icon: KeyRound, group: "System" },
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
  // "audit-log" is a second, filterable view of the same /audit endpoint as "audit" — both are
  // gated by the one existing helix.panel.audit permission, not a separate node.
  const allowed = (id: string) => isIntegratedPanel(id)
    ? panels.some((p) => p.id === id)
    : identity.admin || identity.views.includes(id === "audit-log" ? "audit" : id)
  const groups = ["Testphase", "Platform", "Observability", "System"]
  const extensions = panels.filter((p) => !isIntegratedPanel(p.id))

  const toggleTheme = () => {
    const next = !dark
    setDark(next)
    applyTheme(next ? "dark" : "light")
  }

  return (
    <div className="flex h-screen bg-background text-foreground">
      {/* Sidebar: wood in both modes (§17) — the theme-wood scope gives it the dark token set. */}
      <aside className="theme-wood flex w-60 flex-col border-r-[3px] border-[var(--sidebar-border)] bg-sidebar text-sidebar-foreground">
        <div className="flex flex-col items-start gap-1 px-5 pt-5 pb-4">
          <Wordmark size={30} mark />
          <div className="pl-1 font-display text-xs tracking-[0.08em] text-[var(--parchment-lo)]">Staff Panel</div>
        </div>
        <nav className="flex-1 overflow-y-auto px-3 pb-4">
          {groups.map((group) => {
            const items = NAV.filter((n) => n.group === group && allowed(n.id))
            if (!items.length) return null
            return (
              <div key={group} className="mb-3">
                <div className="px-2 py-1.5 font-display text-xs tracking-[0.08em] text-gold/80">{group}</div>
                {items.map((n) => (
                  <NavButton key={n.id} active={props.view === n.id} icon={<n.icon className="size-4" />}
                    label={n.label} onClick={() => props.onNavigate(n.id)} />
                ))}
              </div>
            )
          })}
          {extensions.length > 0 && (
            <div className="mb-3">
              <div className="px-2 py-1.5 font-display text-xs tracking-[0.08em] text-gold/80">Extensions</div>
              {extensions.map((p) => (
                <NavButton key={p.id} active={props.view === "panel:" + p.id} icon={<Puzzle className="size-4" />}
                  label={p.title} onClick={() => props.onNavigate("panel:" + p.id)} />
              ))}
            </div>
          )}
        </nav>
        <div className="border-t-[3px] border-[var(--sidebar-border)] p-3">
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="sm" className="flex-1 justify-start text-sidebar-foreground hover:bg-sidebar-accent"
              onClick={toggleTheme} title={dark ? "Switch to parchment (light)" : "Switch to wood (dark)"}>
              {dark ? <Moon className="size-4" /> : <Sun className="size-4" />} {dark ? "Wood" : "Parchment"}
            </Button>
            <Button variant="ghost" size="icon" className="text-sidebar-foreground hover:bg-sidebar-accent" onClick={props.onLogout} title="Sign out"><LogOut className="size-4" /></Button>
          </div>
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between border-b-[3px] border-border px-6 py-3">
          <h1 className="kl-title pb-1 text-[22px] leading-tight">{props.title}</h1>
          <div className="flex items-center gap-2">
            <Badge variant="secondary">{identity.admin ? "admin" : identity.name}</Badge>
            {props.version && <Badge variant="outline" className="font-mono text-xs">v{props.version}</Badge>}
          </div>
        </header>
        <main className="flex-1 overflow-y-auto p-6">{props.children}</main>
      </div>
    </div>
  )
}

function NavButton({ active, icon, label, onClick }: { active: boolean; icon: ReactNode; label: string; onClick: () => void }) {
  const iconRef = useRef<HTMLSpanElement>(null)
  // Nudge the icon when this entry becomes the active view — a small, quick
  // acknowledgement of the navigation without any layout shift.
  useEffect(() => {
    if (!active || !iconRef.current) return
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return
    animate(iconRef.current, { translateX: [-3, 0], scale: [0.85, 1], duration: 180, ease: "outQuad" })
  }, [active])
  return (
    <button
      onClick={onClick}
      className={cn(
        "mb-1 flex w-full items-center gap-2.5 rounded-full border-2 px-3 py-[7px] text-left text-sm font-bold transition-[background-color,color,transform] duration-150 ease-out focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring",
        // Active entry: gold pill with ink outline and its own highlight/bottom edge (§17).
        active
          ? "border-[var(--ink-lo)] bg-sidebar-primary text-sidebar-primary-foreground shadow-[inset_0_3px_0_var(--gold-hi),inset_0_-4px_0_var(--gold-lo),0_2px_0_var(--ink-lo)]"
          : "border-transparent text-sidebar-foreground hover:bg-sidebar-accent active:translate-y-[2px]",
      )}
    >
      <span ref={iconRef} className="inline-flex">{icon}</span>
      <span className="truncate">{label}</span>
    </button>
  )
}
