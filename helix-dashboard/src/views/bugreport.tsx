import { useCallback, useEffect, useState } from "react"
import { toast } from "sonner"
import { Paperclip, RefreshCw, X } from "lucide-react"
import { action, type ActionResult } from "@/lib/api"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"

type Variant = "success" | "info" | "warning" | "destructive" | "secondary"

const SEVERITY: Record<string, string> = { minor: "Kleinigkeit", annoying: "Störend", blocking: "Blockierend", crash: "Absturz", exploit: "Exploit" }
const SEVERITY_HINT: Record<string, string> = {
  minor: "Schönheitsfehler, Tippfehler, kleine Ungereimtheiten.",
  annoying: "Funktioniert, aber nervt oder ist falsch.",
  blocking: "Du kommst nicht weiter, etwas geht gar nicht.",
  crash: "Server oder Client stürzt ab bzw. trennt dich.",
  exploit: "Ausnutzbarer Fehler (Duplizieren, Geld, Rechte …). Nur das Team und du sehen die Meldung – bitte nicht weitererzählen oder ausnutzen.",
}
const SEVERITY_VARIANT: Record<string, Variant> = { minor: "secondary", annoying: "warning", blocking: "warning", crash: "destructive", exploit: "destructive" }
const CATEGORY: Record<string, string> = { mineclash: "MineClash", citybuild: "CityBuild", lobby: "Lobby", network: "Netzwerk", graphics: "Grafik/Texte", other: "Sonstiges" }
const STATUS: Record<string, string> = {
  new: "Neu", confirmed: "Bestätigt", in_progress: "In Arbeit", fixed: "Behoben", closed: "Geschlossen",
  rejected: "Abgelehnt", duplicate: "Duplikat", not_a_bug: "Kein Bug",
}
const STATUS_VARIANT: Record<string, Variant> = {
  new: "info", confirmed: "warning", in_progress: "warning", fixed: "success", closed: "secondary",
  rejected: "destructive", duplicate: "secondary", not_a_bug: "secondary",
}
const ERRORS: Record<string, (detail: string) => string> = {
  cooldown: (d) => `Bitte warte noch ${d} Sekunden bis zur nächsten Meldung.`,
  daily_limit: () => "Du hast heute schon die maximale Zahl an Meldungen abgeschickt.",
  agreement_required: () => "Du musst zuerst im Spiel die Tester-Vereinbarung annehmen (/testervereinbarung).",
  not_tester: () => "Bug-Meldungen sind nur für Tester freigeschaltet.",
  testphase_closed: () => "Die Testphase ist geschlossen.",
  invalid_title: () => "Der Titel muss 1–80 Zeichen lang sein.",
  invalid_description: () => "Die Beschreibung darf höchstens 4000 Zeichen haben.",
  invalid_text: () => "Der Kommentar muss 1–4000 Zeichen lang sein.",
  comment_cooldown: (d) => `Bitte warte noch ${d} Sekunden bis zum nächsten Kommentar.`,
  comment_limit: () => "Du hast heute schon die maximale Zahl an Kommentaren geschrieben.",
  too_large: () => "Die Datei ist zu groß.",
  invalid_mime: () => "Nur PNG, JPEG, GIF, WebP oder Text-Dateien sind erlaubt.",
  upload_limit: () => "Du hast schon zu viele Uploads gleichzeitig offen – warte kurz.",
  attachment_limit: () => "Diese Meldung hat schon die maximale Zahl an Anhängen.",
  busy: () => "Gerade laufen zu viele Uploads – versuch es gleich nochmal.",
  forbidden: () => "Das ist nicht deine Meldung.",
  not_found: () => "Nicht gefunden.",
}
/** Attachment types the node accepts – anything else is never rendered. */
const SAFE_MIME = /^(image\/(png|jpeg|gif|webp)|text\/plain)$/
const ACCEPT = "image/png,image/jpeg,image/gif,image/webp"
const CHUNK = 48000

interface Me { name: string; uuid?: string; staff?: boolean }
interface Limits { maxAttachmentBytes: number; testphase?: boolean; cooldownSeconds?: number; maxPerDay?: number }
interface Attachment { id: string; name: string; size: number }
interface Report {
  nr: number; title: string; description: string; severity: string; category: string; status: string
  createdAt: number; updatedAt: number; points?: number; pointsGranted?: boolean; duplicateOf?: number | null
  attachments?: Attachment[]
}
interface Comment { author?: { name: string } | null; staff?: boolean; at: number; text: string }
interface ReportEvent { at: number; actor?: string; type: string; to?: string | null; note?: string | null }
interface Detail { report: Report; comments?: Comment[]; events?: ReportEvent[] }
/** A loaded attachment, already validated: `src` is a data URL of an allowed type. */
interface LoadedAttachment { id: string; name: string; size: number; mime: string; src: string | null }

const when = (ms: number) => new Date(ms).toLocaleString("de-DE")
const errorText = (r: ActionResult) => ERRORS[r.lines[0]]?.(r.lines[1] ?? "") ?? r.lines[1] ?? r.lines[0] ?? "Fehler"
function parse<T>(r: ActionResult, fallback: T): T {
  try { return JSON.parse(r.lines[0]) as T } catch { return fallback }
}

/** Calls an action and toasts a readable German error; network/permission errors too. */
async function call(name: string, ...args: string[]): Promise<ActionResult> {
  try {
    const r = await action(name, ...args)
    if (!r.success) toast.error(errorText(r))
    return r
  } catch (e) {
    toast.error((e as Error).message)
    return { success: false, lines: [] }
  }
}

function readBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result).split(",")[1] ?? "")
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

/** Uploads a screenshot to a report: one call when small, otherwise in chunks. */
async function upload(nr: number, file: File, limits: Limits): Promise<boolean> {
  if (file.size > limits.maxAttachmentBytes) {
    toast.error(`Zu groß (max. ${Math.round(limits.maxAttachmentBytes / 1024)} KB)`)
    return false
  }
  const mime = /\.(txt|log)$/i.test(file.name) && !file.type.startsWith("image/") ? "text/plain" : file.type
  if (!SAFE_MIME.test(mime)) { toast.error(ERRORS.invalid_mime("")); return false }
  const b64 = await readBase64(file)
  const name = file.name.replace(/\s+/g, "_")
  if (b64.length <= CHUNK) return (await call("feedback.attach", String(nr), name, mime, b64)).success
  const id = "panel-" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
  const count = Math.ceil(b64.length / CHUNK)
  for (let i = 0; i < count; i++) {
    const r = await call("feedback.attach.chunk", id, String(i), String(count), b64.slice(i * CHUNK, (i + 1) * CHUNK))
    if (!r.success) return false
  }
  return (await call("feedback.attach.finish", id, String(nr), name, mime)).success
}

/** Report form for testers plus their own reports with status, team replies and history. */
export function BugReportView() {
  const [me, setMe] = useState<Me>({ name: "" })
  const [limits, setLimits] = useState<Limits>({ maxAttachmentBytes: 2097152, testphase: true })
  const [tab, setTab] = useState("new")
  const [mine, setMine] = useState<Report[] | null>(null)
  const [detail, setDetail] = useState<Detail | null>(null)

  useEffect(() => {
    action("feedback.whoami").then((r) => r.success && setMe(parse(r, { name: "" }))).catch(() => undefined)
    action("feedback.limits").then((r) => r.success && setLimits((l) => parse(r, l))).catch(() => undefined)
  }, [])

  // Testers always act as themselves (the node ignores these names); team members keep their name on the report.
  const self = useCallback(() => (me.uuid ? { uuid: me.uuid, name: me.name } : { name: me.name }), [me])

  const loadMine = useCallback(async () => {
    const r = await call("feedback.mine", me.uuid || "-", "100")
    setMine(r.success ? parse<Report[]>(r, []) : [])
  }, [me.uuid])

  const openDetail = useCallback(async (nr: number) => {
    const r = await call("feedback.get", String(nr))
    if (r.success) setDetail(parse<Detail | null>(r, null))
  }, [])

  useEffect(() => { if (tab === "mine") loadMine() }, [tab, loadMine])

  const created = async (nr: number) => { setTab("mine"); await openDetail(nr) }

  return (
    <div className="flex flex-col gap-4">
      {limits.testphase === false && (
        <Card><CardContent className="py-4 font-bold">Die Testphase ist geschlossen – neue Meldungen sind gerade nicht möglich.</CardContent></Card>
      )}
      <Tabs value={tab} onValueChange={setTab}>
        <div className="flex items-center justify-between gap-3">
          <TabsList>
            <TabsTrigger value="new">Neue Meldung</TabsTrigger>
            <TabsTrigger value="mine">Meine Meldungen</TabsTrigger>
          </TabsList>
          {me.name && <span className="text-sm text-muted-foreground">angemeldet als {me.name}</span>}
        </div>
        <TabsContent value="new" className="mt-4">
          <NewReport limits={limits} self={self} onCreated={created} />
        </TabsContent>
        <TabsContent value="mine" className="mt-4 flex flex-col gap-4">
          <Card>
            <CardHeader className="flex-row items-center justify-between gap-3">
              <CardTitle className="text-base">Meine Meldungen</CardTitle>
              <Button size="sm" variant="outline" onClick={() => { loadMine(); if (detail) openDetail(detail.report.nr) }}>
                <RefreshCw className="size-4" /> Aktualisieren
              </Button>
            </CardHeader>
            <CardContent className="p-0">
              {mine === null && <div className="py-8 text-center text-muted-foreground">lädt …</div>}
              {mine?.length === 0 && <div className="py-8 text-center text-muted-foreground">Du hast noch nichts gemeldet.</div>}
              {!!mine?.length && (
                <Table>
                  <TableHeader>
                    <TableRow><TableHead>#</TableHead><TableHead>Titel</TableHead><TableHead>Schwere</TableHead><TableHead>Bereich</TableHead><TableHead>Status</TableHead><TableHead>Geändert</TableHead></TableRow>
                  </TableHeader>
                  <TableBody>
                    {mine.map((b) => (
                      <TableRow key={b.nr} className="cursor-pointer" onClick={() => openDetail(b.nr)}>
                        <TableCell className="font-bold">#{b.nr}</TableCell>
                        <TableCell className="font-bold">
                          {b.title}{!!b.attachments?.length && <Paperclip className="ml-1.5 inline size-3.5 opacity-60" />}
                        </TableCell>
                        <TableCell><Badge variant={SEVERITY_VARIANT[b.severity] ?? "secondary"}>{SEVERITY[b.severity] ?? b.severity}</Badge></TableCell>
                        <TableCell>{CATEGORY[b.category] ?? b.category}</TableCell>
                        <TableCell><Badge variant={STATUS_VARIANT[b.status] ?? "secondary"}>{STATUS[b.status] ?? b.status}</Badge></TableCell>
                        <TableCell className="text-sm text-muted-foreground">{when(b.updatedAt)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
          {detail && (
            <ReportDetail detail={detail} limits={limits} self={self}
              onClose={() => setDetail(null)} onChanged={() => { openDetail(detail.report.nr); loadMine() }} />
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}

function NewReport({ limits, self, onCreated }: {
  limits: Limits
  self: () => { uuid?: string; name: string }
  onCreated: (nr: number) => void
}) {
  const [category, setCategory] = useState("mineclash")
  const [severity, setSeverity] = useState("annoying")
  const [title, setTitle] = useState("")
  const [description, setDescription] = useState("")
  const [file, setFile] = useState<File | null>(null)
  const [fileKey, setFileKey] = useState(0)
  const [sending, setSending] = useState(false)

  const send = async () => {
    if (!title.trim()) { toast.error("Bitte einen Titel angeben."); return }
    if (severity === "exploit" && !window.confirm("Als Exploit melden? Die Meldung sehen nur das Team und du.")) return
    setSending(true)
    try {
      const r = await call("feedback.create", JSON.stringify({ reporter: self(), severity, category, title: title.trim(), description: description.trim() }))
      if (!r.success) return
      const report = parse<Report | null>(r, null)
      let note = ""
      if (report && file) note = (await upload(report.nr, file, limits)) ? " mit Screenshot" : " (Screenshot konnte nicht hochgeladen werden)"
      toast.success(`Meldung #${report?.nr ?? "?"} gesendet${note}. Danke!`)
      setTitle(""); setDescription(""); setFile(null); setFileKey((k) => k + 1)
      if (report) onCreated(report.nr)
    } finally {
      setSending(false)
    }
  }

  return (
    <Card>
      <CardHeader><CardTitle className="text-base">Bug melden</CardTitle></CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="grid gap-4 md:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <Label>Bereich</Label>
            <Select value={category} onValueChange={setCategory}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>{Object.entries(CATEGORY).map(([k, v]) => <SelectItem key={k} value={k}>{v}</SelectItem>)}</SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label>Schwere</Label>
            <Select value={severity} onValueChange={setSeverity}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>{Object.entries(SEVERITY).map(([k, v]) => <SelectItem key={k} value={k}>{v}</SelectItem>)}</SelectContent>
            </Select>
            <span className={severity === "exploit" ? "text-sm font-bold text-destructive" : "text-sm text-muted-foreground"}>{SEVERITY_HINT[severity]}</span>
          </div>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="br-title">Titel</Label>
          <Input id="br-title" maxLength={80} placeholder="Kurz und eindeutig, z. B. „Truhe öffnet sich nicht nach dem Krieg“"
            value={title} onChange={(e) => setTitle(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="br-desc">Beschreibung</Label>
          <Textarea id="br-desc" maxLength={4000} className="min-h-[140px]"
            placeholder={"Was hast du gemacht? Was ist passiert? Was hättest du erwartet?\nWo (Server, Welt, Koordinaten) und wann?"}
            value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="br-file">Screenshot (optional)</Label>
          <Input key={fileKey} id="br-file" type="file" accept={ACCEPT} className="pt-2"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          <span className="text-sm text-muted-foreground">
            Bild bis {Math.round(limits.maxAttachmentBytes / 1024)} KB. Pause zwischen zwei Meldungen: {limits.cooldownSeconds ?? 60} s,
            höchstens {limits.maxPerDay ?? 20} pro Tag.
          </span>
        </div>
        <div className="flex items-center gap-3">
          <Button onClick={send} disabled={sending || limits.testphase === false}>{sending ? "wird gesendet …" : "Meldung abschicken"}</Button>
        </div>
      </CardContent>
    </Card>
  )
}

function ReportDetail({ detail, limits, self, onClose, onChanged }: {
  detail: Detail
  limits: Limits
  self: () => { uuid?: string; name: string }
  onClose: () => void
  onChanged: () => void
}) {
  const { report: b, comments = [], events = [] } = detail
  const [reply, setReply] = useState("")
  const [file, setFile] = useState<File | null>(null)
  const [fileKey, setFileKey] = useState(0)
  const [attachments, setAttachments] = useState<LoadedAttachment[]>([])
  const [zoom, setZoom] = useState<LoadedAttachment | null>(null)

  useEffect(() => {
    let alive = true
    ;(async () => {
      const loaded: LoadedAttachment[] = []
      for (const a of b.attachments ?? []) {
        let mime = "", src: string | null = null
        try {
          const r = await action("feedback.attachment.mine", a.id)
          mime = r.success ? String(r.lines[0] ?? "") : ""
          // Only allowed types become a data URL; the payload is reduced to base64 characters.
          if (SAFE_MIME.test(mime)) src = `data:${mime};base64,${String(r.lines[1] ?? "").replace(/[^A-Za-z0-9+/=]/g, "")}`
        } catch { /* shown as not loadable */ }
        loaded.push({ id: a.id, name: String(a.name ?? ""), size: Number(a.size) || 0, mime, src })
      }
      if (alive) setAttachments(loaded)
    })()
    return () => { alive = false }
  }, [b.nr, b.attachments])

  const sendReply = async () => {
    const text = reply.trim()
    if (!text) return
    const r = await call("feedback.comment", JSON.stringify({ nr: b.nr, author: self(), text }))
    if (r.success) { toast.success("Antwort gesendet"); setReply(""); onChanged() }
  }
  const attach = async () => {
    if (!file) { toast.error("Datei wählen"); return }
    if (await upload(b.nr, file, limits)) { toast.success("Screenshot angehängt"); setFile(null); setFileKey((k) => k + 1); onChanged() }
  }

  return (
    <Card>
      <CardHeader className="flex-row items-start justify-between gap-3">
        <CardTitle className="text-base">#{b.nr} · {b.title}</CardTitle>
        <div className="flex items-center gap-2">
          <Badge variant={SEVERITY_VARIANT[b.severity] ?? "secondary"}>{SEVERITY[b.severity] ?? b.severity}</Badge>
          <Badge variant={STATUS_VARIANT[b.status] ?? "secondary"}>{STATUS[b.status] ?? b.status}</Badge>
          <Button size="icon" variant="ghost" onClick={onClose} title="Schließen"><X className="size-4" /></Button>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <div className="flex flex-wrap gap-2">
          <Badge variant="outline">{CATEGORY[b.category] ?? b.category}</Badge>
          <Badge variant="outline">gemeldet {when(b.createdAt)}</Badge>
          {b.pointsGranted && <Badge variant="success">+{b.points} Punkte</Badge>}
          {b.duplicateOf && <Badge variant="outline">Duplikat von #{b.duplicateOf}</Badge>}
        </div>
        <p className="whitespace-pre-wrap text-sm leading-relaxed">{b.description || <span className="text-muted-foreground">Keine Beschreibung.</span>}</p>

        <section className="flex flex-col gap-2">
          <h3 className="font-display text-sm">Anhänge</h3>
          {!attachments.length && <span className="text-sm text-muted-foreground">{b.attachments?.length ? "lädt …" : "Keine Anhänge."}</span>}
          <div className="flex flex-wrap gap-3">
            {attachments.map((a) => (
              <figure key={a.id} className="flex w-44 flex-col gap-1">
                {a.src && a.mime.startsWith("image/")
                  ? <img src={a.src} alt={a.name} onClick={() => setZoom(a)}
                      className="h-28 w-44 cursor-zoom-in rounded-lg border-2 border-border object-cover" />
                  : <div className="grid h-28 w-44 place-items-center rounded-lg border-2 border-border bg-muted text-xs text-muted-foreground">
                      {a.src ? "Textdatei" : "nicht ladbar"}
                    </div>}
                <figcaption className="truncate text-xs text-muted-foreground">{a.name} · {Math.round(a.size / 1024)} KB</figcaption>
              </figure>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Input key={fileKey} type="file" accept={ACCEPT} className="max-w-xs pt-2" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            <Button size="sm" variant="outline" onClick={attach}>Screenshot anhängen</Button>
          </div>
        </section>

        <section className="flex flex-col gap-2">
          <h3 className="font-display text-sm">Antworten</h3>
          {!comments.length && <span className="text-sm text-muted-foreground">Noch keine Antworten.</span>}
          {comments.map((c, i) => (
            <div key={i} className="rounded-xl border-2 border-border bg-muted/40 p-3">
              <div className="flex items-center gap-2 text-sm">
                <span className="font-bold">{c.author?.name ?? "Team"}</span>
                {c.staff && <Badge variant="info">Team</Badge>}
                <span className="text-muted-foreground">· {when(c.at)}</span>
              </div>
              <p className="mt-1 whitespace-pre-wrap text-sm">{c.text}</p>
            </div>
          ))}
          <Textarea placeholder="Ergänzung oder Antwort ans Team …" className="min-h-[80px]" maxLength={4000}
            value={reply} onChange={(e) => setReply(e.target.value)} />
          <div><Button size="sm" onClick={sendReply} disabled={!reply.trim()}>Antworten</Button></div>
        </section>

        <section className="flex flex-col gap-1">
          <h3 className="font-display text-sm">Verlauf</h3>
          {!events.length && <span className="text-sm text-muted-foreground">–</span>}
          {events.map((e, i) => (
            <div key={i} className="text-sm text-muted-foreground">
              {when(e.at)} · {e.actor ?? ""} · {e.type}{e.to ? ` → ${STATUS[e.to] ?? e.to}` : ""}{e.note ? ` – ${e.note}` : ""}
            </div>
          ))}
        </section>
      </CardContent>
      <Dialog open={!!zoom} onOpenChange={(o) => !o && setZoom(null)}>
        <DialogContent className="max-w-5xl">
          <DialogHeader><DialogTitle>{zoom?.name}</DialogTitle></DialogHeader>
          {zoom?.src && <img src={zoom.src} alt={zoom.name} className="max-h-[75vh] w-full rounded-lg object-contain" />}
        </DialogContent>
      </Dialog>
    </Card>
  )
}
