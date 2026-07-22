import { useState } from "react"
import { Plus } from "lucide-react"
import { toast } from "sonner"
import { api, type ActionResult, type TaskDefinition } from "@/lib/api"
import { usePoll } from "@/lib/use-poll"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** Tasks table with create / deploy / delete. */
export function TasksView() {
  const { data, reload } = usePoll(() => api<TaskDefinition[]>("/tasks"), 5000)

  const deploy = async (name: string) => {
    try { const i = await api<{ id: string }>(`/tasks/${name}/services`, { method: "POST" }); toast.success("Deployed " + i.id) }
    catch (e) { toast.error((e as Error).message) }
  }
  const del = async (name: string) => {
    if (!confirm(`Delete task ${name}?`)) return
    try { await api(`/tasks/${name}`, { method: "DELETE" }); toast.success("Deleted " + name); reload() }
    catch (e) { toast.error((e as Error).message) }
  }

  return (
    <Card>
      <CardContent className="p-0">
        <div className="flex items-center justify-between p-4">
          <div className="text-sm text-muted-foreground">{data?.length ?? 0} tasks</div>
          <CreateTaskDialog onCreated={reload} />
        </div>
        <Table>
          <TableHeader><TableRow><TableHead>Name</TableHead><TableHead>Platform</TableHead><TableHead>Executor</TableHead><TableHead /></TableRow></TableHeader>
          <TableBody>
            {(data ?? []).map((t) => (
              <TableRow key={t.name}>
                <TableCell className="font-medium">{t.name}</TableCell>
                <TableCell><Badge variant={t.environment?.proxy ? "warning" : "secondary"}>{t.environment?.name}</Badge></TableCell>
                <TableCell className="text-sm text-muted-foreground">{t.executor}</TableCell>
                <TableCell>
                  <div className="flex justify-end gap-2">
                    <Button size="sm" onClick={() => deploy(t.name)}>Deploy</Button>
                    <Button size="sm" variant="destructive" onClick={() => del(t.name)}>Delete</Button>
                  </div>
                </TableCell>
              </TableRow>
            ))}
            {data && data.length === 0 && (
              <TableRow><TableCell colSpan={4} className="py-10 text-center text-muted-foreground">No tasks yet — create one.</TableCell></TableRow>
            )}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}

function CreateTaskDialog({ onCreated }: { onCreated: () => void }) {
  const [open, setOpen] = useState(false)
  const [f, setF] = useState({ name: "", env: "PAPER", version: "1.21.11", exec: "PROCESS", kind: "false", min: "1", max: "1", memory: "1024", players: "100", port: "25565", autoscale: "false" })
  const set = (k: string, v: string) => setF((p) => ({ ...p, [k]: v }))

  const create = async () => {
    if (!f.name.trim()) return toast.error("Name is required")
    const args = [f.name, f.env, f.version, `executor=${f.exec}`, `static=${f.kind}`, `min=${f.min}`, `max=${f.max}`, `memory=${f.memory}`, `maxplayers=${f.players}`, `startport=${f.port}`, `autoscale=${f.autoscale}`]
    try {
      const r = await api<ActionResult>("/actions", { method: "POST", body: { action: "task.create", arguments: args } })
      if (r.success) { toast.success(r.lines[0] || "created"); setOpen(false); onCreated() } else toast.error(r.lines[0] || "failed")
    } catch (e) { toast.error((e as Error).message) }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button size="sm"><Plus className="size-4" /> New task</Button></DialogTrigger>
      <DialogContent>
        <DialogHeader><DialogTitle>New task</DialogTitle></DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Name"><Input value={f.name} onChange={(e) => set("name", e.target.value)} placeholder="Lobby" /></Field>
          <Field label="Platform">
            <Select value={f.env} onValueChange={(v) => { set("env", v); set("port", v === "VELOCITY" ? "25577" : "25565"); set("version", v === "VELOCITY" ? "3.4.0" : "1.21.11") }}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent><SelectItem value="PAPER">Paper (backend)</SelectItem><SelectItem value="VELOCITY">Velocity (proxy)</SelectItem></SelectContent>
            </Select>
          </Field>
          <Field label="Version"><Input value={f.version} onChange={(e) => set("version", e.target.value)} /></Field>
          <Field label="Executor">
            <Select value={f.exec} onValueChange={(v) => set("exec", v)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent><SelectItem value="PROCESS">PROCESS</SelectItem><SelectItem value="DOCKER">DOCKER</SelectItem></SelectContent>
            </Select>
          </Field>
          <Field label="Kind">
            <Select value={f.kind} onValueChange={(v) => set("kind", v)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent><SelectItem value="false">dynamic</SelectItem><SelectItem value="true">static</SelectItem></SelectContent>
            </Select>
          </Field>
          <Field label="Autoscale">
            <Select value={f.autoscale} onValueChange={(v) => set("autoscale", v)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent><SelectItem value="false">off</SelectItem><SelectItem value="true">on</SelectItem></SelectContent>
            </Select>
          </Field>
          <Field label="Min services"><Input type="number" value={f.min} onChange={(e) => set("min", e.target.value)} /></Field>
          <Field label="Max services"><Input type="number" value={f.max} onChange={(e) => set("max", e.target.value)} /></Field>
          <Field label="Memory (MB)"><Input type="number" value={f.memory} onChange={(e) => set("memory", e.target.value)} /></Field>
          <Field label="Start port"><Input type="number" value={f.port} onChange={(e) => set("port", e.target.value)} /></Field>
        </div>
        <DialogFooter><Button onClick={create}>Create task</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div className="grid gap-1.5"><Label>{label}</Label>{children}</div>
}
