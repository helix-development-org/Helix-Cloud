import { toast } from "sonner"
import { api, type ActionResult, type OnlinePlayer } from "@/lib/api"
import { usePoll } from "@/lib/use-poll"
import { uptime } from "@/lib/format"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** Online players with message / kick / ban actions. */
export function PlayersView() {
  const { data, reload } = usePoll(() => api<OnlinePlayer[]>("/players"), 5000)

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

  return (
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
  )
}
