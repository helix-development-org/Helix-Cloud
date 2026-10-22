import { useEffect, useMemo, useState } from "react"
import { toast } from "sonner"
import { Copy, Search } from "lucide-react"
import { action } from "@/lib/api"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** One command of the tester guide (`feedback.testguide`). */
interface GuideEntry {
  id: string
  mode: string
  command: string
  summary: string
  expected: string
  audience: string
  permission?: string
}

const MODES = ["Netzwerk", "Lobby", "MineClash", "CityBuild", "Testphase"]
const AUDIENCES: Record<string, string> = { Spieler: "Spieler", Tester: "Tester", Team: "nur Team" }
const AUDIENCE_VARIANT: Record<string, "success" | "info" | "secondary"> = { Spieler: "success", Tester: "info", Team: "secondary" }
const ALL = "all"

/** Copies a command to the clipboard, with a fallback for browsers that block the clipboard API. */
async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const ta = document.createElement("textarea")
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand("copy")
    ta.remove()
    if (!ok) { toast.error("Kopieren nicht erlaubt"); return }
  }
  toast.success(`Kopiert: ${text}`)
}

/** Every in-game command with what it does and what should happen when testing it. */
export function TestGuideView({ onReport }: { onReport?: () => void }) {
  const [entries, setEntries] = useState<GuideEntry[] | null>(null)
  const [error, setError] = useState("")
  const [query, setQuery] = useState("")
  const [mode, setMode] = useState(ALL)
  const [audience, setAudience] = useState(ALL)

  useEffect(() => {
    action("feedback.testguide")
      .then((r) => {
        if (!r.success) { setError(r.lines[0] || "Fehler"); setEntries([]); return }
        const parsed = JSON.parse(r.lines.join("\n")) as { entries?: GuideEntry[] }
        setEntries(parsed.entries ?? [])
      })
      .catch((e) => { setError((e as Error).message); setEntries([]) })
  }, [])

  const shown = useMemo(() => {
    const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean)
    return (entries ?? []).filter((e) => {
      if (mode !== ALL && e.mode !== mode) return false
      if (audience !== ALL && e.audience !== audience) return false
      const hay = `${e.command} ${e.summary} ${e.expected} ${e.permission ?? ""} ${e.mode}`.toLowerCase()
      return words.every((w) => hay.includes(w))
    })
  }, [entries, query, mode, audience])

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader className="flex-row items-baseline justify-between gap-3">
          <CardTitle className="text-base">Befehle zum Testen</CardTitle>
          {entries && <span className="text-sm text-muted-foreground">{shown.length} von {entries.length} Befehlen</span>}
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <p className="text-sm leading-relaxed">
            Hier steht jeder Befehl im Spiel mit einer kurzen Erklärung und dem, was beim Testen passieren soll.
            Ein Klick auf einen Befehl kopiert ihn. Passiert etwas anderes als beschrieben, melde es unter{" "}
            {onReport
              ? <button className="font-bold text-primary underline-offset-2 hover:underline" onClick={onReport}>Bug melden</button>
              : <strong>Bug melden</strong>}{" "}
            oder im Spiel mit <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">/bug</code>.
          </p>
          <div className="grid gap-3 md:grid-cols-[2fr_1fr_1fr]">
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 opacity-60" />
              <Input className="pl-9" placeholder="Befehl oder Stichwort, z. B. clan, plot, kronen …" value={query}
                onChange={(e) => setQuery(e.target.value)} />
            </div>
            <Select value={mode} onValueChange={setMode}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>Alle Bereiche</SelectItem>
                {MODES.map((m) => <SelectItem key={m} value={m}>{m}</SelectItem>)}
              </SelectContent>
            </Select>
            <Select value={audience} onValueChange={setAudience}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>Für alle</SelectItem>
                {Object.entries(AUDIENCES).map(([k, v]) => <SelectItem key={k} value={k}>{v}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>
        </CardContent>
      </Card>

      {entries === null && <Card><CardContent className="py-8 text-center text-muted-foreground">lädt …</CardContent></Card>}
      {error && <Card><CardContent className="py-8 text-center text-muted-foreground">Befehls-Übersicht nicht ladbar ({error}).</CardContent></Card>}
      {entries && !error && shown.length === 0 && (
        <Card><CardContent className="py-8 text-center text-muted-foreground">Kein Befehl passt zur Suche.</CardContent></Card>
      )}

      {MODES.filter((m) => shown.some((e) => e.mode === m)).map((m) => {
        const rows = shown.filter((e) => e.mode === m)
        return (
          <Card key={m}>
            <CardHeader className="flex-row items-baseline justify-between gap-3">
              <CardTitle className="text-base">{m}</CardTitle>
              <span className="text-sm text-muted-foreground">{rows.length} Befehle</span>
            </CardHeader>
            <CardContent className="p-0">
              <Table>
                <TableHeader>
                  <TableRow><TableHead>Befehl</TableHead><TableHead>Was er macht</TableHead><TableHead>Das soll passieren</TableHead><TableHead>Für</TableHead></TableRow>
                </TableHeader>
                <TableBody>
                  {rows.map((e) => (
                    <TableRow key={e.id}>
                      <TableCell className="align-top">
                        <button title="Klicken zum Kopieren" onClick={() => copy(e.command)}
                          className="inline-flex items-center gap-1.5 whitespace-nowrap rounded-lg border border-border bg-muted px-2 py-1 font-mono text-[13px] hover:border-primary">
                          {e.command}<Copy className="size-3 opacity-60" />
                        </button>
                        {e.permission && <div className="mt-1 font-mono text-[11px] text-muted-foreground">{e.permission}</div>}
                      </TableCell>
                      <TableCell className="align-top text-sm leading-snug">{e.summary}</TableCell>
                      <TableCell className="align-top text-sm leading-snug text-muted-foreground">{e.expected}</TableCell>
                      <TableCell className="align-top">
                        <Badge variant={AUDIENCE_VARIANT[e.audience] ?? "secondary"}>{AUDIENCES[e.audience] ?? e.audience}</Badge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        )
      })}
    </div>
  )
}
