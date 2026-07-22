import { useEffect, useRef, useState } from "react"
import { toast } from "sonner"
import { api, type ServiceInfo } from "@/lib/api"
import { streamLines } from "@/lib/sse"
import { usePoll } from "@/lib/use-poll"
import { stateVariant, uptime } from "@/lib/format"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** Services table with lifecycle actions and an interactive console. */
export function ServicesView() {
  const { data, reload } = usePoll(() => api<ServiceInfo[]>("/services"), 3000)
  const [console, setConsole] = useState<string | null>(null)

  const act = async (id: string, op: string) => {
    try { await api(`/services/${id}/${op}`, { method: "POST" }); toast.success(`${op} ${id}`); reload() }
    catch (e) { toast.error((e as Error).message) }
  }

  return (
    <Card>
      <CardContent className="p-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Service</TableHead><TableHead>State</TableHead><TableHead>Players</TableHead>
              <TableHead>RAM</TableHead><TableHead>CPU</TableHead>
              <TableHead>Port</TableHead><TableHead>Executor</TableHead><TableHead>Uptime</TableHead><TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {(data ?? []).map((s) => (
              <TableRow key={s.id}>
                <TableCell><span className="font-medium">{s.id}</span> <span className="text-xs text-muted-foreground">{s.environment}</span></TableCell>
                <TableCell><Badge variant={stateVariant(s.state)}>{s.state}</Badge></TableCell>
                <TableCell className="text-sm">{s.onlinePlayers}/{s.maxPlayers}</TableCell>
                <TableCell className="text-sm">
                  {s.memoryUsedMb >= 0 ? (
                    <span className="flex items-center gap-2">
                      <span className="font-mono text-xs">{s.memoryUsedMb}/{s.memoryMaxMb} MB</span>
                      <span className="h-1.5 w-14 overflow-hidden rounded-full bg-secondary">
                        <span className="block h-full bg-primary" style={{ width: `${Math.min(100, (s.memoryUsedMb / Math.max(1, s.memoryMaxMb)) * 100)}%` }} />
                      </span>
                    </span>
                  ) : <span className="text-muted-foreground">—</span>}
                </TableCell>
                <TableCell className="font-mono text-xs">{s.cpuPercent >= 0 ? s.cpuPercent.toFixed(1) + "%" : "—"}</TableCell>
                <TableCell className="font-mono text-sm">{s.port}</TableCell>
                <TableCell className="text-sm text-muted-foreground">{s.executor}</TableCell>
                <TableCell className="text-sm text-muted-foreground">{uptime(s.startedAtEpochMs)}</TableCell>
                <TableCell>
                  <div className="flex justify-end gap-2">
                    <Button size="sm" variant="outline" onClick={() => setConsole(s.id)}>Console</Button>
                    <Button size="sm" variant="secondary" onClick={() => act(s.id, "stop")}>Stop</Button>
                    <Button size="sm" variant="destructive" onClick={() => act(s.id, "kill")}>Kill</Button>
                  </div>
                </TableCell>
              </TableRow>
            ))}
            {data && data.length === 0 && (
              <TableRow><TableCell colSpan={9} className="py-10 text-center text-muted-foreground">No services running — start one from Tasks.</TableCell></TableRow>
            )}
          </TableBody>
        </Table>
      </CardContent>
      {console && <ConsoleDialog id={console} onClose={() => setConsole(null)} />}
    </Card>
  )
}

function ConsoleDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const [lines, setLines] = useState<string[]>([])
  const [cmd, setCmd] = useState("")
  const boxRef = useRef<HTMLDivElement>(null)

  const load = async () => {
    try { const r = await api<{ lines: string[] }>(`/services/${id}/logs?tail=300`); setLines(r.lines) } catch { /* ignore */ }
  }
  useEffect(() => {
    // live SSE stream; falls back to 2s polling if streaming fails
    const abort = new AbortController()
    let poll: ReturnType<typeof setInterval> | null = null
    streamLines(`/services/${id}/logs/stream`, (line) => {
      setLines((prev) => [...prev.slice(-999), line])
    }, abort.signal).catch(() => {
      if (abort.signal.aborted) return
      load()
      poll = setInterval(load, 2000)
    })
    return () => { abort.abort(); if (poll) clearInterval(poll) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])
  useEffect(() => { boxRef.current?.scrollTo(0, boxRef.current.scrollHeight) }, [lines])

  const send = async () => {
    if (!cmd.trim()) return
    try { await api(`/services/${id}/command`, { method: "POST", body: { command: cmd } }); setCmd(""); setTimeout(load, 200) }
    catch (e) { toast.error((e as Error).message) }
  }

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-w-3xl">
        <DialogHeader><DialogTitle>Console — {id}</DialogTitle></DialogHeader>
        <div ref={boxRef} className="h-[55vh] overflow-y-auto rounded-md border bg-black/40 p-3 font-mono text-xs leading-relaxed">
          {lines.length ? lines.map((l, i) => (
            <div key={i} className={l.includes("ERROR") ? "text-destructive" : l.includes("WARN") ? "text-[var(--warning)]" : "text-muted-foreground"}>{l}</div>
          )) : <div className="text-muted-foreground">(no output)</div>}
        </div>
        <div className="flex items-center gap-2">
          <span className="font-mono text-success">&gt;</span>
          <Input className="font-mono" value={cmd} autoFocus placeholder="say hello, list, …"
            onChange={(e) => setCmd(e.target.value)} onKeyDown={(e) => e.key === "Enter" && send()} />
          <Button size="sm" onClick={send}>Send</Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
