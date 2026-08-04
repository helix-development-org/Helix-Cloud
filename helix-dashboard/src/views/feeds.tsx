import { useEffect, useMemo, useRef, useState } from "react"
import { api, type ActionResult, type AuditEntry, type EventEntry } from "@/lib/api"
import { streamLines } from "@/lib/sse"
import { usePoll } from "@/lib/use-poll"
import { ago } from "@/lib/format"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
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

/** Launcher console: the node log streamed live, plus a CLI-style action prompt. */
export function LogsView() {
  const [lines, setLines] = useState<string[]>([])
  const [input, setInput] = useState("")
  const [busy, setBusy] = useState(false)
  const history = useRef<string[]>([])
  const historyIndex = useRef(-1)
  const box = useRef<HTMLDivElement>(null)

  const append = (extra: string[]) => setLines((prev) => [...prev.slice(-1499), ...extra])
  const run = async () => {
    const line = input.trim()
    if (!line || busy) return
    setInput("")
    history.current = [...history.current.slice(-49), line]
    historyIndex.current = -1
    append(["> " + line])
    const [action, ...args] = line.split(/\s+/)
    setBusy(true)
    try {
      const r = await api<ActionResult>("/actions", {
        method: "POST",
        body: JSON.stringify({ action, arguments: args }),
      })
      append((r.lines.length ? r.lines : [r.success ? "done" : "failed"]).map(
        (l) => (r.success ? "  " : "ERROR ") + l,
      ))
    } catch (e) {
      append(["ERROR " + (e as Error).message])
    } finally { setBusy(false) }
  }
  const onKey = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowUp") {
      e.preventDefault()
      const h = history.current
      if (!h.length) return
      historyIndex.current = historyIndex.current < 0 ? h.length - 1 : Math.max(0, historyIndex.current - 1)
      setInput(h[historyIndex.current])
    } else if (e.key === "ArrowDown") {
      e.preventDefault()
      const h = history.current
      if (historyIndex.current < 0) return
      historyIndex.current = historyIndex.current + 1
      if (historyIndex.current >= h.length) { historyIndex.current = -1; setInput("") } else setInput(h[historyIndex.current])
    }
  }
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
      <div ref={box} className="h-[64vh] overflow-y-auto rounded-t-xl bg-black/40 p-4 font-mono text-xs leading-relaxed">
        {lines.map((l, i) => (
          <div key={i} className={l.startsWith("> ") ? "text-foreground" : l.includes("ERROR") ? "text-destructive" : l.includes("WARN") ? "text-[var(--warning)]" : "text-muted-foreground"}>{l}</div>
        ))}
      </div>
      <form
        className="flex items-center gap-2 border-t border-border bg-black/60 p-2"
        onSubmit={(e) => { e.preventDefault(); run() }}
      >
        <span className="pl-2 font-mono text-xs text-muted-foreground">&gt;</span>
        <Input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKey}
          placeholder={busy ? "running…" : "action ausführen — z. B. service.list, task.pause Lobby stop, actions.list"}
          className="h-8 border-0 bg-transparent font-mono text-xs shadow-none focus-visible:ring-0"
        />
      </form>
    </CardContent></Card>
  )
}

/** Plain, unfiltered activity trail (nav: "Logs" — the former simple Audit view). */
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

/**
 * Full, filterable audit trail (nav: "Audit"). Fetches a generous unfiltered
 * window and filters client-side so the category list and the other filters
 * never fight each other, and so typing in a filter never needs a round-trip.
 */
export function AuditLogView() {
  const [category, setCategory] = useState("all")
  const [actor, setActor] = useState("")
  const [search, setSearch] = useState("")
  const { data } = usePoll(() => api<AuditEntry[]>("/audit?limit=1000"), 5000)

  const categories = useMemo(
    () => Array.from(new Set((data ?? []).map((e) => e.category))).sort(),
    [data],
  )
  const filtered = useMemo(() => {
    const a = actor.trim().toLowerCase()
    const s = search.trim().toLowerCase()
    return (data ?? []).filter((e) =>
      (category === "all" || e.category === category) &&
      (!a || e.actor.toLowerCase().includes(a)) &&
      (!s || e.summary.toLowerCase().includes(s)),
    )
  }, [data, category, actor, search])

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader><CardTitle className="text-sm font-medium text-muted-foreground">Filter</CardTitle></CardHeader>
        <CardContent className="flex flex-wrap items-center gap-3">
          <div className="w-48">
            <Select value={category} onValueChange={setCategory}>
              <SelectTrigger><SelectValue placeholder="Category" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All categories</SelectItem>
                {categories.map((c) => <SelectItem key={c} value={c}>{c}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
          <Input className="w-56" placeholder="Player / actor…" value={actor} onChange={(e) => setActor(e.target.value)} />
          <Input className="w-72" placeholder="Search action, service, details…" value={search} onChange={(e) => setSearch(e.target.value)} />
          <span className="text-xs text-muted-foreground">{filtered.length} of {data?.length ?? 0} entries</span>
        </CardContent>
      </Card>
      <Card><CardContent className="p-0">
        <Table>
          <TableHeader><TableRow><TableHead>Time</TableHead><TableHead>Category</TableHead><TableHead>Actor</TableHead><TableHead>Summary</TableHead><TableHead>Outcome</TableHead></TableRow></TableHeader>
          <TableBody>
            {filtered.map((e, i) => (
              <TableRow key={i}>
                <TableCell className="whitespace-nowrap text-xs text-muted-foreground">{ago(e.epochMs)}</TableCell>
                <TableCell><Badge variant="secondary">{e.category}</Badge></TableCell>
                <TableCell className="text-sm">{e.actor}</TableCell>
                <TableCell className="max-w-md text-sm">{e.summary}</TableCell>
                <TableCell><Badge variant={e.outcome === "ok" ? "success" : e.outcome === "denied" ? "warning" : e.outcome === "error" ? "destructive" : "secondary"}>{e.outcome}</Badge></TableCell>
              </TableRow>
            ))}
            {data && !filtered.length && <TableRow><TableCell colSpan={5} className="py-10 text-center text-muted-foreground">No matching audit entries.</TableCell></TableRow>}
          </TableBody>
        </Table>
      </CardContent></Card>
    </div>
  )
}
