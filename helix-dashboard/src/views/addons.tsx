import { RefreshCw } from "lucide-react"
import { toast } from "sonner"
import { api, type ActionResult, type AddonInfo } from "@/lib/api"
import { usePoll } from "@/lib/use-poll"
import { stateVariant } from "@/lib/format"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** Installed addons with enable / disable / hot-reload. */
export function AddonsView() {
  const { data, reload } = usePoll(() => api<AddonInfo[]>("/addons"), 6000)

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
          <TableHeader><TableRow><TableHead>ID</TableHead><TableHead>Name</TableHead><TableHead>Version</TableHead><TableHead>State</TableHead><TableHead /></TableRow></TableHeader>
          <TableBody>
            {(data ?? []).map((a) => (
              <TableRow key={a.manifest.id}>
                <TableCell className="font-mono text-sm">{a.manifest.id}</TableCell>
                <TableCell>{a.manifest.name}</TableCell>
                <TableCell className="text-sm text-muted-foreground">{a.manifest.version}</TableCell>
                <TableCell><Badge variant={stateVariant(a.state)}>{a.state}</Badge></TableCell>
                <TableCell><div className="flex justify-end"><Button size="sm" variant={a.state === "ENABLED" ? "secondary" : "default"} onClick={() => toggle(a.manifest.id, a.state === "ENABLED")}>{a.state === "ENABLED" ? "Disable" : "Enable"}</Button></div></TableCell>
              </TableRow>
            ))}
            {data && !data.length && <TableRow><TableCell colSpan={5} className="py-10 text-center text-muted-foreground">No addons installed.</TableCell></TableRow>}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}
