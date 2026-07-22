import { useEffect, useRef, useState } from "react"
import { api, type AuditEntry, type EventEntry } from "@/lib/api"
import { streamLines } from "@/lib/sse"
import { usePoll } from "@/lib/use-poll"
import { ago } from "@/lib/format"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** Event timeline. */
export function EventsView() {
  const { data } = usePoll(() => api<EventEntry[]>("/events?limit=200"), 4000)
  return (
    <Card><CardContent className="flex flex-col gap-2 p-5">
      {(data ?? []).map((e, i) => (
        <div key={i} className="flex items-center gap-3 text-sm">
          <Badge variant={e.level === "warn" ? "warning" : e.level === "error" ? "destructive" : "secondary"}>{e.category}</Badge>
          <span className="flex-1">{e.message}</span>
          <span className="text-xs text-muted-foreground">{ago(e.epochMs)}</span>
        </div>
      ))}
      {data && !data.length && <div className="text-sm text-muted-foreground">No events yet.</div>}
    </CardContent></Card>
  )
}

/** Node log, streamed live via SSE with a polling fallback. */
export function LogsView() {
  const [lines, setLines] = useState<string[]>([])
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const abort = new AbortController()
    let poll: ReturnType<typeof setInterval> | null = null
    const fallback = async () => {
      try { const r = await api<{ lines: string[] }>("/logs?tail=400"); setLines(r.lines) } catch { /* ignore */ }
    }
    streamLines("/logs/stream", (line) => {
      setLines((prev) => [...prev.slice(-1499), line])
    }, abort.signal).catch(() => {
      if (abort.signal.aborted) return
      fallback()
      poll = setInterval(fallback, 2000)
    })
    return () => { abort.abort(); if (poll) clearInterval(poll) }
  }, [])
  useEffect(() => { box.current?.scrollTo(0, box.current.scrollHeight) }, [lines])
  return (
    <Card><CardContent className="p-0">
      <div ref={box} className="h-[70vh] overflow-y-auto rounded-xl bg-black/40 p-4 font-mono text-xs leading-relaxed">
        {lines.map((l, i) => (
          <div key={i} className={l.includes("ERROR") ? "text-destructive" : l.includes("WARN") ? "text-[var(--warning)]" : "text-muted-foreground"}>{l}</div>
        ))}
      </div>
    </CardContent></Card>
  )
}

/** Complete audit trail. */
export function AuditView() {
  const { data } = usePoll(() => api<AuditEntry[]>("/audit?limit=300"), 5000)
  return (
    <Card><CardContent className="p-0">
      <Table>
        <TableHeader><TableRow><TableHead>Time</TableHead><TableHead>Category</TableHead><TableHead>Actor</TableHead><TableHead>Summary</TableHead><TableHead>Outcome</TableHead></TableRow></TableHeader>
        <TableBody>
          {(data ?? []).map((e, i) => (
            <TableRow key={i}>
              <TableCell className="whitespace-nowrap text-xs text-muted-foreground">{ago(e.epochMs)}</TableCell>
              <TableCell><Badge variant="secondary">{e.category}</Badge></TableCell>
              <TableCell className="text-sm">{e.actor}</TableCell>
              <TableCell className="max-w-md truncate text-sm">{e.summary}</TableCell>
              <TableCell><Badge variant={e.outcome === "ok" ? "success" : e.outcome === "denied" ? "warning" : e.outcome === "error" ? "destructive" : "secondary"}>{e.outcome}</Badge></TableCell>
            </TableRow>
          ))}
          {data && !data.length && <TableRow><TableCell colSpan={5} className="py-10 text-center text-muted-foreground">No audit entries.</TableCell></TableRow>}
        </TableBody>
      </Table>
    </CardContent></Card>
  )
}
