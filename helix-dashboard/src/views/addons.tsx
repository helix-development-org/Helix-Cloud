import { Fragment, useState } from "react"
import { ChevronDown, ChevronRight, Play, RefreshCw } from "lucide-react"
import { toast } from "sonner"
import { api, type ActionResult, type AddonAction, type AddonInfo } from "@/lib/api"
import { usePoll } from "@/lib/use-poll"
import { stateVariant } from "@/lib/format"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** Installed addons with enable / disable / hot-reload and a per-addon action runner. */
export function AddonsView() {
  const { data, reload } = usePoll(() => api<AddonInfo[]>("/addons"), 6000)
  const [expanded, setExpanded] = useState<string | null>(null)

  const toggle = async (id: string, enabled: boolean) => {
    try { await api(`/addons/${id}/${enabled ? "disable" : "enable"}`, { method: "POST" }); toast.success(id); reload() }
    catch (e) { toast.error((e as Error).message) }
  }
  const reloadNew = async () => {
    try { const r = await api<ActionResult>("/actions", { method: "POST", body: { action: "addon.list.reload", arguments: [] } }); toast.success(r.lines[0] || "reloaded"); reload() }
    catch (e) { toast.error((e as Error).message) }
  }

  return (
    <Card>
      <CardContent className="p-0">
        <div className="flex items-center justify-between p-4">
          <div className="text-sm text-muted-foreground">{data?.length ?? 0} addons</div>
          <Button size="sm" variant="outline" onClick={reloadNew}><RefreshCw className="size-4" /> Load new</Button>
        </div>
        <Table>
          <TableHeader><TableRow><TableHead className="w-8" /><TableHead>ID</TableHead><TableHead>Name</TableHead><TableHead>Version</TableHead><TableHead>State</TableHead><TableHead /></TableRow></TableHeader>
          <TableBody>
            {(data ?? []).map((a) => {
              const runnable = (a.actions ?? []).filter((ac) => !ac.playerCommand)
              const open = expanded === a.manifest.id
              return (
                <Fragment key={a.manifest.id}>
                  <TableRow className={runnable.length ? "cursor-pointer" : ""}
                    onClick={() => runnable.length && setExpanded(open ? null : a.manifest.id)}>
                    <TableCell className="pr-0 text-muted-foreground">
                      {runnable.length > 0 && (open ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />)}
                    </TableCell>
                    <TableCell className="font-mono text-sm">{a.manifest.id}</TableCell>
                    <TableCell>{a.manifest.name}</TableCell>
                    <TableCell className="text-sm text-muted-foreground">{a.manifest.version}</TableCell>
                    <TableCell>
                      <Badge variant={stateVariant(a.state)} title={a.failureReason ?? undefined}>{a.state}</Badge>
                      {a.state === "FAILED" && a.failureReason && (
                        <div className="mt-1 max-w-xs truncate text-xs text-muted-foreground" title={a.failureReason}>{a.failureReason}</div>
                      )}
                    </TableCell>
                    <TableCell onClick={(e) => e.stopPropagation()}>
                      <div className="flex justify-end"><Button size="sm" variant={a.state === "ENABLED" ? "secondary" : "default"} onClick={() => toggle(a.manifest.id, a.state === "ENABLED")}>{a.state === "ENABLED" ? "Disable" : "Enable"}</Button></div>
                    </TableCell>
                  </TableRow>
                  {open && (
                    <TableRow>
                      <TableCell colSpan={6} className="bg-secondary/20 p-4">
                        <div className="flex flex-col gap-3">
                          {runnable.map((action) => <ActionRunner key={action.name} action={action} />)}
                        </div>
                      </TableCell>
                    </TableRow>
                  )}
                </Fragment>
              )
            })}
            {data && !data.length && <TableRow><TableCell colSpan={6} className="py-10 text-center text-muted-foreground">No addons installed.</TableCell></TableRow>}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}

/**
 * One runnable addon action: argument input (usage as placeholder), run
 * button, inline result. Player commands never reach this component — they
 * need an in-game context and are filtered out by the caller.
 */
function ActionRunner({ action }: { action: AddonAction }) {
  const [args, setArgs] = useState("")
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<ActionResult | null>(null)

  const run = async () => {
    setBusy(true)
    setResult(null)
    try {
      const r = await api<ActionResult>("/actions", {
        method: "POST",
        body: { action: action.name, arguments: args.trim() ? args.trim().split(/\s+/) : [] },
      })
      setResult(r)
      if (!r.success) toast.error(r.lines[0] || `${action.name} failed`)
    } catch (e) {
      toast.error((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="rounded-lg border bg-background p-3">
      <div className="flex items-baseline justify-between gap-3">
        <div>
          <span className="font-mono text-sm">{action.name}</span>
          {action.permission && <span className="ml-2 rounded bg-secondary px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground">{action.permission}</span>}
          <div className="text-xs text-muted-foreground">{action.description}</div>
        </div>
      </div>
      <div className="mt-2 flex items-center gap-2">
        <Input
          className="font-mono text-sm"
          placeholder={action.usage.replace(action.name, "").trim() || "no arguments"}
          value={args}
          onChange={(e) => setArgs(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && !busy && run()}
        />
        <Button size="sm" disabled={busy} onClick={run}><Play className="size-4" /> Run</Button>
      </div>
      {result && (
        <div className={"mt-2 rounded-md border p-2 font-mono text-xs " + (result.success ? "" : "border-destructive/50 text-destructive")}>
          {result.lines.length ? result.lines.map((line, index) => <div key={index}>{line}</div>) : (result.success ? "ok" : "failed")}
        </div>
      )}
    </div>
  )
}
