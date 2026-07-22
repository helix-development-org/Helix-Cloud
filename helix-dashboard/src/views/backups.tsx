import { useState } from "react"
import { Archive } from "lucide-react"
import { toast } from "sonner"
import { api } from "@/lib/api"
import { usePoll } from "@/lib/use-poll"
import { ago } from "@/lib/format"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

interface BackupInfo {
  serviceId: string
  fileName: string
  sizeBytes: number
  createdAtEpochMs: number
}
interface Overview {
  workspaces: { serviceId: string; running: boolean }[]
  backups: BackupInfo[]
}

function size(bytes: number): string {
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + " MiB"
  return Math.max(1, Math.round(bytes / 1024)) + " KiB"
}

/** Workspace backups: create, restore (stopped services only), delete. */
export function BackupsView() {
  const { data, reload } = usePoll(() => api<Overview>("/backups"), 5000)
  const [target, setTarget] = useState("")

  const create = async () => {
    if (!target) return toast.error("Pick a static service")
    try {
      const info = await api<BackupInfo>(`/services/${encodeURIComponent(target)}/backups`, { method: "POST" })
      toast.success(`Created ${info.fileName}`)
      reload()
    } catch (e) { toast.error((e as Error).message) }
  }
  const restore = async (b: BackupInfo) => {
    if (!confirm(`Restore ${b.fileName} into ${b.serviceId}? Current workspace content is replaced.`)) return
    try {
      await api(`/backups/${encodeURIComponent(b.serviceId)}/${encodeURIComponent(b.fileName)}/restore`, { method: "POST" })
      toast.success("Restored " + b.fileName)
    } catch (e) { toast.error((e as Error).message) }
  }
  const del = async (b: BackupInfo) => {
    if (!confirm(`Delete ${b.serviceId}/${b.fileName}?`)) return
    try {
      await api(`/backups/${encodeURIComponent(b.serviceId)}/${encodeURIComponent(b.fileName)}`, { method: "DELETE" })
      toast.success("Deleted " + b.fileName)
      reload()
    } catch (e) { toast.error((e as Error).message) }
  }

  const running = new Map((data?.workspaces ?? []).map((w) => [w.serviceId, w.running]))

  return (
    <div className="flex flex-col gap-5">
      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><Archive className="size-4" /> Create backup</CardTitle></CardHeader>
        <CardContent className="flex items-center gap-3">
          <div className="w-64">
            <Select value={target} onValueChange={setTarget}>
              <SelectTrigger><SelectValue placeholder="Static service workspace…" /></SelectTrigger>
              <SelectContent>
                {(data?.workspaces ?? []).map((w) => (
                  <SelectItem key={w.serviceId} value={w.serviceId}>{w.serviceId}{w.running ? " (running)" : ""}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <Button size="sm" onClick={create}>Create backup</Button>
          {data && data.workspaces.length === 0 && (
            <span className="text-sm text-muted-foreground">No static workspaces yet — static tasks create them on first start.</span>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow><TableHead>Service</TableHead><TableHead>Archive</TableHead><TableHead>Size</TableHead><TableHead>Created</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {(data?.backups ?? []).map((b) => (
                <TableRow key={b.serviceId + b.fileName}>
                  <TableCell className="font-medium">{b.serviceId}</TableCell>
                  <TableCell className="font-mono text-xs">{b.fileName}</TableCell>
                  <TableCell className="text-sm text-muted-foreground">{size(b.sizeBytes)}</TableCell>
                  <TableCell className="text-sm text-muted-foreground">{ago(b.createdAtEpochMs)}</TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-2">
                      {running.get(b.serviceId) && <Badge variant="warning">running</Badge>}
                      <Button size="sm" variant="outline" disabled={running.get(b.serviceId)}
                        title={running.get(b.serviceId) ? "Stop the service first" : ""}
                        onClick={() => restore(b)}>Restore</Button>
                      <Button size="sm" variant="destructive" onClick={() => del(b)}>Delete</Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
              {data && data.backups.length === 0 && (
                <TableRow><TableCell colSpan={5} className="py-10 text-center text-muted-foreground">No backups yet — create one above or schedule <code>backup.create &lt;serviceId&gt;</code> as a job.</TableCell></TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}
