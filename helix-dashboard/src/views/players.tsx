import { useState } from "react"
import { toast } from "sonner"
import { api, type ActionResult, type OnlinePlayer, type PlayerLookupView } from "@/lib/api"
import { usePoll } from "@/lib/use-poll"
import { uptime } from "@/lib/format"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** Human labels for the raw per-addon export keys the lookup card renders. */
const SOURCE_TITLES: Record<string, string> = {
  bans: "Ban", permissions: "Permissions", clan: "Clan", economy: "Economy",
  moderation: "Warnings", friends: "Friends",
}

/** Online players (message/kick/ban) plus a staff lookup for any player by name. */
export function PlayersView() {
  const { data, reload } = usePoll(() => api<OnlinePlayer[]>("/players"), 5000)
  const [query, setQuery] = useState("")
  const [lookup, setLookup] = useState<PlayerLookupView | null>(null)
  const [searching, setSearching] = useState(false)

  const act = async (name: string, op: "message" | "kick" | "ban") => {
    let body: Record<string, unknown> = {}
    if (op === "message") { const t = prompt(`Message to ${name}:`); if (!t) return; body = { value: t } }
    else if (op === "kick") { const r = prompt(`Kick ${name} — reason:`, ""); if (r === null) return; body = { value: r } }
    else { const d = prompt(`Ban ${name} — duration (e.g. 7d, empty = permanent):`, ""); if (d === null) return
      const r = prompt(`Ban ${name} — reason:`, ""); if (r === null) return; body = { value: r, duration: d } }
    try { const res = await api<ActionResult>(`/players/${encodeURIComponent(name)}/${op}`, { method: "POST", body })
      toast.success(res.lines?.[0] || (res.success ? "Done" : "Failed")); reload() }
    catch (e) { toast.error((e as Error).message) }
  }

  const search = async () => {
    const name = query.trim()
    if (!name) return
    setSearching(true)
    try { setLookup(await api<PlayerLookupView>(`/players/lookup?name=${encodeURIComponent(name)}`)) }
    catch (e) { toast.error((e as Error).message); setLookup(null) }
    finally { setSearching(false) }
  }

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader><CardTitle className="text-base">Player lookup</CardTitle></CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex gap-2">
            <Input placeholder="Player name or UUID" value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && search()} />
            <Button onClick={search} disabled={searching || !query.trim()}>Look up</Button>
          </div>
          {lookup && <PlayerLookupCard lookup={lookup} />}
        </CardContent>
      </Card>
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow><TableHead>Name</TableHead><TableHead>UUID</TableHead><TableHead>Proxy</TableHead><TableHead>Online</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {(data ?? []).map((p) => (
                <TableRow key={p.name}>
                  <TableCell className="font-medium">{p.name}</TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground">{p.uuid || "—"}</TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground">{p.proxyServiceId || "—"}</TableCell>
                  <TableCell className="text-sm text-muted-foreground">{uptime(p.joinedAtEpochMs)}</TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-2">
                      <Button size="sm" variant="outline" onClick={() => act(p.name, "message")}>Message</Button>
                      <Button size="sm" variant="secondary" onClick={() => act(p.name, "kick")}>Kick</Button>
                      <Button size="sm" variant="destructive" onClick={() => act(p.name, "ban")}>Ban</Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
              {data && data.length === 0 && (
                <TableRow><TableCell colSpan={5} className="py-10 text-center text-muted-foreground">No players online.</TableCell></TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}

/** Renders one addon's raw JSON export as a flat key/value list. */
function SourceFields({ raw }: { raw: string }) {
  let parsed: unknown
  try { parsed = JSON.parse(raw) } catch { return <p className="text-muted-foreground">{raw}</p> }
  if (typeof parsed !== "object" || parsed === null) return <p>{String(parsed)}</p>
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
      {Object.entries(parsed as Record<string, unknown>).map(([key, value]) => (
        <div key={key} className="contents">
          <dt className="text-muted-foreground">{key}</dt>
          <dd className="break-all">{typeof value === "object" ? JSON.stringify(value) : String(value)}</dd>
        </div>
      ))}
    </dl>
  )
}

/** Aggregate staff view of a looked-up player: presence plus every addon's data. */
function PlayerLookupCard({ lookup }: { lookup: PlayerLookupView }) {
  const sourceEntries = Object.entries(lookup.sources)
  return (
    <div className="rounded-md border p-4">
      <div className="mb-3 flex items-center gap-2">
        <span className="font-medium">{lookup.name}</span>
        <Badge variant={lookup.online ? "success" : "secondary"}>{lookup.online ? "Online" : "Offline"}</Badge>
        {lookup.online && <span className="text-xs text-muted-foreground">on {lookup.proxyServiceId} · {uptime(lookup.joinedAtEpochMs)}</span>}
      </div>
      {lookup.uuid && <p className="mb-3 font-mono text-xs text-muted-foreground">{lookup.uuid}</p>}
      {sourceEntries.length === 0 ? (
        <p className="text-sm text-muted-foreground">No addon has data about this player.</p>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          {sourceEntries.map(([owner, raw]) => (
            <div key={owner}>
              <div className="mb-1 text-xs font-semibold uppercase text-muted-foreground">{SOURCE_TITLES[owner] ?? owner}</div>
              <SourceFields raw={raw} />
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
