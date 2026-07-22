import { useState } from "react"
import { Plus } from "lucide-react"
import { toast } from "sonner"
import { api, type ScheduledJob } from "@/lib/api"
import { usePoll } from "@/lib/use-poll"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** Recurring scheduled jobs. */
export function SchedulesView() {
  const { data, reload } = usePoll(() => api<ScheduledJob[]>("/schedules"), 0)
  const [f, setF] = useState({ id: "", action: "", args: "", every: "0", daily: "" })
  const set = (k: string, v: string) => setF((p) => ({ ...p, [k]: v }))

  const create = async () => {
    if (!f.id.trim() || !f.action.trim()) return toast.error("ID and action required")
    const everyMinutes = parseInt(f.every) || 0
    if (everyMinutes <= 0 && !f.daily.trim()) return toast.error("Set an interval or a daily time")
    try {
      await api("/schedules", { method: "POST", body: { id: f.id.trim(), action: f.action.trim(), arguments: f.args.trim() ? f.args.trim().split(/\s+/) : [], everyMinutes, dailyAt: f.daily.trim() || null, enabled: true } })
      toast.success("Created " + f.id); setF({ id: "", action: "", args: "", every: "0", daily: "" }); reload()
    } catch (e) { toast.error((e as Error).message) }
  }
  const run = async (id: string) => { try { await api(`/schedules/${encodeURIComponent(id)}/run`, { method: "POST" }); toast.success("Ran " + id) } catch (e) { toast.error((e as Error).message) } }
  const del = async (id: string) => { if (!confirm(`Delete ${id}?`)) return; try { await api(`/schedules/${encodeURIComponent(id)}`, { method: "DELETE" }); toast.success("Deleted " + id); reload() } catch (e) { toast.error((e as Error).message) } }

  return (
    <div className="flex flex-col gap-5">
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow><TableHead>ID</TableHead><TableHead>Action</TableHead><TableHead>Schedule</TableHead><TableHead>On</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {(data ?? []).map((j) => (
                <TableRow key={j.id}>
                  <TableCell className="font-medium">{j.id}</TableCell>
                  <TableCell className="font-mono text-xs">{j.action} {j.arguments.join(" ")}</TableCell>
                  <TableCell className="text-sm">{j.everyMinutes > 0 ? `every ${j.everyMinutes}m` : j.dailyAt ? `daily ${j.dailyAt}` : "—"}</TableCell>
                  <TableCell><Badge variant={j.enabled ? "success" : "secondary"}>{j.enabled ? "on" : "off"}</Badge></TableCell>
                  <TableCell><div className="flex justify-end gap-2"><Button size="sm" variant="outline" onClick={() => run(j.id)}>Run now</Button><Button size="sm" variant="destructive" onClick={() => del(j.id)}>Delete</Button></div></TableCell>
                </TableRow>
              ))}
              {data && !data.length && <TableRow><TableCell colSpan={5} className="py-10 text-center text-muted-foreground">No scheduled jobs.</TableCell></TableRow>}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>New job</CardTitle></CardHeader>
        <CardContent className="grid grid-cols-2 gap-3 lg:grid-cols-3">
          <F label="ID"><Input value={f.id} onChange={(e) => set("id", e.target.value)} placeholder="daily-restart" /></F>
          <F label="Action"><Input value={f.action} onChange={(e) => set("action", e.target.value)} placeholder="player.broadcast" /></F>
          <F label="Arguments"><Input value={f.args} onChange={(e) => set("args", e.target.value)} placeholder="&cRestart soon" /></F>
          <F label="Every N minutes (0=off)"><Input type="number" value={f.every} onChange={(e) => set("every", e.target.value)} /></F>
          <F label="Daily at (HH:mm)"><Input value={f.daily} onChange={(e) => set("daily", e.target.value)} placeholder="04:00" /></F>
          <div className="flex items-end"><Button onClick={create}><Plus className="size-4" /> Create job</Button></div>
        </CardContent>
      </Card>
    </div>
  )
}

function F({ label, children }: { label: string; children: React.ReactNode }) {
  return <div className="grid gap-1.5"><Label>{label}</Label>{children}</div>
}
