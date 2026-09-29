import { useMemo, useState } from "react"
import { toast } from "sonner"
import { api, type ActionResult, type Identity } from "@/lib/api"
import { closeOverlay, mc } from "@/lib/ingame"
import { usePoll } from "@/lib/use-poll"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"

/** One online player as reported by the `helix.admin` addon (`admin.players`). */
interface AdminPlayer {
  name: string
  uuid: string
  service: string
  world: string
  x: number
  y: number
  z: number
  health: number
  food: number
  gamemode: string
  ping: number
  frozen: boolean
  vanished: boolean
}

interface ServerRow { service: string; tps: string; players: string }
interface ServerInfo { service: string; tps: number; players: number; maxPlayers: number; memoryUsedMb: number; memoryMaxMb: number; worlds: string[]; version: string }

const GAMEMODES: [string, string][] = [["SURVIVAL", "Survival"], ["CREATIVE", "Creative"], ["ADVENTURE", "Adventure"], ["SPECTATOR", "Spectator"]]

/** Runs a node action for the signed-in session (`POST /actions`) and toasts the outcome. */
async function node(action: string, ...args: string[]) {
  try {
    const res = await api<ActionResult>("/actions", { method: "POST", body: { action, arguments: args } })
    if (res.success) toast.success(res.lines?.[0] || "Done")
    else toast.error(res.lines?.[0] || "Failed")
    return res
  } catch (e) {
    toast.error((e as Error).message)
    return null
  }
}

/** Runs an in-game op through the mod and toasts errors (and optional success text). */
async function game(op: string, args: Record<string, unknown> = {}, success?: (r: Record<string, unknown>) => string) {
  try {
    const r = await mc(op, args)
    if (r && (r as { remote?: string }).remote) toast.success(`On ${(r as { remote: string }).remote} – sending you there`)
    else if (success) toast.success(success(r))
    return r
  } catch (e) {
    toast.error((e as Error).message)
    return null
  }
}

/**
 * Compact txAdmin-style quick menu shown by the Helix Admin client mod (Right Shift): own tools and the
 * world (Main), every online player network-wide with in-game and moderation actions (Players) and the
 * servers (Server). "Open dashboard" switches to the full dashboard in the same overlay.
 */
export function IngameMenuView({ identity, onOpenDashboard }: { identity: Identity; onOpenDashboard: () => void }) {
  return (
    <div className="dark pointer-events-none fixed inset-0 text-foreground">
      <div className="pointer-events-auto absolute left-4 top-4 bottom-4 flex w-[400px] flex-col overflow-hidden rounded-xl border border-border bg-background/95 shadow-2xl backdrop-blur">
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <div>
            <div className="text-sm font-semibold">Helix Admin</div>
            <div className="text-xs text-muted-foreground">{identity.name}</div>
          </div>
          <Button size="sm" variant="ghost" onClick={closeOverlay}>✕</Button>
        </div>
        <Tabs defaultValue="main" className="flex min-h-0 flex-1 flex-col">
          <TabsList className="mx-4 mt-3 grid grid-cols-3">
            <TabsTrigger value="main">Main</TabsTrigger>
            <TabsTrigger value="players">Players</TabsTrigger>
            <TabsTrigger value="server">Server</TabsTrigger>
          </TabsList>
          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3">
            <TabsContent value="main"><MainTab /></TabsContent>
            <TabsContent value="players"><PlayersTab /></TabsContent>
            <TabsContent value="server"><ServerTab /></TabsContent>
          </div>
        </Tabs>
        <div className="border-t border-border p-3">
          <Button className="w-full" onClick={onOpenDashboard}>Open dashboard</Button>
        </div>
      </div>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mb-4">
      <div className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">{title}</div>
      {children}
    </div>
  )
}

function MainTab() {
  const [pos, setPos] = useState({ x: "", y: "", z: "" })
  const [radius, setRadius] = useState("16")
  const [announce, setAnnounce] = useState("")
  const toggle = (label: string, key: string) => (r: Record<string, unknown>) => `${label} ${r[key] ? "on" : "off"}`
  return (
    <>
      <Section title="Self">
        <div className="grid grid-cols-3 gap-2">
          <Button variant="secondary" onClick={() => game("self.noclip", {}, toggle("NoClip", "noclip"))}>NoClip</Button>
          <Button variant="secondary" onClick={() => game("self.heal", {}, () => "Healed")}>Heal</Button>
          <Button variant="secondary" onClick={() => game("self.feed", {}, () => "Fed")}>Feed</Button>
          <Button variant="secondary" onClick={() => game("self.fly", {}, toggle("Fly", "fly"))}>Fly</Button>
          <Button variant="secondary" onClick={() => game("self.vanish", {}, toggle("Vanish", "vanish"))}>Vanish</Button>
          <Button variant="secondary" onClick={() => game("self.spawn", {}, () => "At spawn")}>Spawn</Button>
        </div>
        <div className="mt-2 grid grid-cols-4 gap-2">
          {GAMEMODES.map(([mode, label]) => (
            <Button key={mode} size="sm" variant="outline" onClick={() => game("self.gamemode", { mode }, () => label)}>{label}</Button>
          ))}
        </div>
      </Section>
      <Section title="Teleport to coordinates">
        <div className="flex gap-2">
          {(["x", "y", "z"] as const).map((k) => (
            <Input key={k} placeholder={k.toUpperCase()} value={pos[k]} onChange={(e) => setPos({ ...pos, [k]: e.target.value })} />
          ))}
          <Button onClick={() => game("self.tp", { x: pos.x, y: pos.y, z: pos.z }, () => "Teleported")}>Go</Button>
        </div>
      </Section>
      <Section title="World">
        <div className="grid grid-cols-4 gap-2">
          {["day", "noon", "night", "midnight"].map((t) => (
            <Button key={t} size="sm" variant="outline" onClick={() => game("world.time", { value: t }, () => `Time: ${t}`)}>{t}</Button>
          ))}
        </div>
        <div className="mt-2 grid grid-cols-3 gap-2">
          {["clear", "rain", "thunder"].map((w) => (
            <Button key={w} size="sm" variant="outline" onClick={() => game("world.weather", { value: w }, () => `Weather: ${w}`)}>{w}</Button>
          ))}
        </div>
        <div className="mt-2 flex gap-2">
          <Input value={radius} onChange={(e) => setRadius(e.target.value)} placeholder="Radius" />
          <Button variant="destructive" onClick={() => game("world.clear", { radius }, (r) => `Removed ${r.removed} entities`)}>Clear area</Button>
        </div>
      </Section>
      <Section title="Announcement (network)">
        <div className="flex gap-2">
          <Input value={announce} onChange={(e) => setAnnounce(e.target.value)} placeholder="Message to everyone" />
          <Button onClick={() => announce.trim() && node("admin.announce", announce).then(() => setAnnounce(""))}>Send</Button>
        </div>
      </Section>
    </>
  )
}

function PlayersTab() {
  const { data, reload } = usePoll(() => api<ActionResult>("/actions", { method: "POST", body: { action: "admin.players", arguments: [] } })
    .then((r) => (r.success ? JSON.parse(r.lines[0] || "[]") as AdminPlayer[] : [])), 3000)
  const [query, setQuery] = useState("")
  const [selected, setSelected] = useState<string | null>(null)
  const players = useMemo(() => (data ?? []).filter((p) => p.name.toLowerCase().includes(query.toLowerCase())), [data, query])
  const current = (data ?? []).find((p) => p.name === selected)

  if (selected) return <PlayerDetail name={selected} player={current} onBack={() => setSelected(null)} onChanged={reload} />
  return (
    <>
      <Input className="mb-3" placeholder={`Search ${data?.length ?? 0} players…`} value={query} onChange={(e) => setQuery(e.target.value)} />
      <div className="flex flex-col gap-1">
        {players.map((p) => (
          <button key={p.name} onClick={() => setSelected(p.name)}
            className="flex items-center justify-between rounded-md px-3 py-2 text-left hover:bg-accent">
            <div>
              <div className="text-sm font-medium">{p.name}</div>
              <div className="text-xs text-muted-foreground">{p.service} · {p.world}</div>
            </div>
            <div className="flex items-center gap-1">
              {p.frozen && <Badge variant="secondary">frozen</Badge>}
              {p.vanished && <Badge variant="secondary">vanish</Badge>}
              <span className="text-xs text-muted-foreground">♥ {p.health} · {p.ping} ms</span>
            </div>
          </button>
        ))}
        {data && players.length === 0 && <div className="py-8 text-center text-sm text-muted-foreground">No players.</div>}
      </div>
    </>
  )
}

function PlayerDetail({ name, player, onBack, onChanged }: { name: string; player?: AdminPlayer; onBack: () => void; onChanged: () => void }) {
  const [text, setText] = useState("")
  const [duration, setDuration] = useState("7d")
  const run = (op: string, args: Record<string, unknown> = {}, success?: string) =>
    game(op, { name, ...args }, success ? () => success : undefined).then(onChanged)
  return (
    <>
      <Button size="sm" variant="ghost" className="mb-2 -ml-2" onClick={onBack}>← Players</Button>
      <div className="mb-3">
        <div className="text-lg font-semibold">{name}</div>
        {player ? (
          <div className="text-xs text-muted-foreground">
            {player.service} · {player.world} ({player.x}, {player.y}, {player.z}) · {player.gamemode.toLowerCase()} · ♥ {player.health} · 🍗 {player.food} · {player.ping} ms
          </div>
        ) : <div className="text-xs text-muted-foreground">offline</div>}
      </div>
      <Section title="In-game">
        <div className="grid grid-cols-3 gap-2">
          <Button variant="secondary" onClick={() => run("player.goto", {}, `Teleported to ${name}`)}>Go to</Button>
          <Button variant="secondary" onClick={() => run("player.bring", {}, `${name} brought`)}>Bring</Button>
          <Button variant="secondary" onClick={() => run("player.spectate", {}, `Spectating ${name}`)}>Spectate</Button>
          <Button variant="secondary" onClick={() => run("player.freeze", {}, player?.frozen ? "Unfrozen" : "Frozen")}>{player?.frozen ? "Unfreeze" : "Freeze"}</Button>
          <Button variant="secondary" onClick={() => run("player.heal", {}, "Healed")}>Heal</Button>
          <Button variant="secondary" onClick={() => run("player.invsee")}>Inventory</Button>
        </div>
        <div className="mt-2 grid grid-cols-4 gap-2">
          {GAMEMODES.map(([mode, label]) => (
            <Button key={mode} size="sm" variant="outline" onClick={() => run("player.gamemode", { mode }, `${name}: ${label}`)}>{label}</Button>
          ))}
        </div>
      </Section>
      <Section title="Moderation">
        <Input className="mb-2" placeholder="Message / reason" value={text} onChange={(e) => setText(e.target.value)} />
        <div className="grid grid-cols-3 gap-2">
          <Button variant="outline" disabled={!text.trim()} onClick={() => node("admin.message", name, text)}>Message</Button>
          <Button variant="outline" disabled={!text.trim()} onClick={() => node("admin.warn", name, text)}>Warn</Button>
          <Button variant="secondary" onClick={() => node("admin.kick", name, ...(text.trim() ? [text] : [])).then(onChanged)}>Kick</Button>
        </div>
        <div className="mt-2 flex gap-2">
          <Input value={duration} onChange={(e) => setDuration(e.target.value)} placeholder="7d / perm" />
          <Button variant="destructive" onClick={() => confirm(`Ban ${name} (${duration})?`) && node("admin.ban", name, duration || "perm", ...(text.trim() ? [text] : [])).then(onChanged)}>Ban</Button>
        </div>
      </Section>
    </>
  )
}

function ServerTab() {
  const { data: info } = usePoll(() => mc<ServerInfo>("server.info").catch(() => null), 5000)
  const { data: servers } = usePoll(() => api<ActionResult>("/actions", { method: "POST", body: { action: "admin.servers", arguments: [] } })
    .then((r) => (r.success ? JSON.parse(r.lines[0] || "[]") as ServerRow[] : [])), 5000)
  return (
    <>
      {info && (
        <Section title="This server">
          <div className="grid grid-cols-2 gap-2 text-sm">
            <Stat label="Service" value={info.service} />
            <Stat label="TPS" value={String(info.tps)} />
            <Stat label="Players" value={`${info.players}/${info.maxPlayers}`} />
            <Stat label="Memory" value={`${info.memoryUsedMb}/${info.memoryMaxMb} MB`} />
            <Stat label="Version" value={info.version} />
            <Stat label="Worlds" value={info.worlds.join(", ")} />
          </div>
        </Section>
      )}
      <Section title="Network">
        <div className="flex flex-col gap-1">
          {(servers ?? []).map((s) => (
            <div key={s.service} className="flex items-center justify-between rounded-md px-3 py-2 hover:bg-accent">
              <div>
                <div className="text-sm font-medium">{s.service}</div>
                <div className="text-xs text-muted-foreground">{s.players} players · {s.tps} TPS</div>
              </div>
              <Button size="sm" variant="outline" onClick={() => confirm(`Restart ${s.service}?`) && node("admin.service.restart", s.service)}>Restart</Button>
            </div>
          ))}
        </div>
      </Section>
    </>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md bg-muted/50 px-3 py-2">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="truncate">{value}</div>
    </div>
  )
}
