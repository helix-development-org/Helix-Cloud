import { toast } from "sonner"
import { api, type ProxyView } from "@/lib/api"
import { usePoll } from "@/lib/use-poll"
import { stateVariant } from "@/lib/format"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Switch } from "@/components/ui/switch"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** Proxy overview: maintenance toggle, proxies and backend routing. */
export function ProxyPageView() {
  const { data, reload } = usePoll(() => api<ProxyView>("/proxy"), 3000)

  const setMaintenance = async (enabled: boolean) => {
    try { await api("/proxy/maintenance", { method: "POST", body: { enabled } }); toast.success(`Maintenance ${enabled ? "on" : "off"}`); reload() }
    catch (e) { toast.error((e as Error).message) }
  }

  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>
  return (
    <div className="flex flex-col gap-5">
      <Card>
        <CardContent className="flex items-center justify-between p-5">
          <div>
            <div className="font-medium">Maintenance mode</div>
            <div className="text-sm text-muted-foreground">Rejects regular joins; only players with the bypass permission may connect.</div>
          </div>
          <Switch checked={data.maintenance} onCheckedChange={setMaintenance} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>Proxies</CardTitle></CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow><TableHead>Service</TableHead><TableHead>State</TableHead><TableHead>Port</TableHead><TableHead>Players</TableHead></TableRow></TableHeader>
            <TableBody>
              {data.proxies.map((p) => (
                <TableRow key={p.id}><TableCell className="font-medium">{p.id}</TableCell><TableCell><Badge variant={stateVariant(p.state)}>{p.state}</Badge></TableCell><TableCell className="font-mono text-sm">{p.port}</TableCell><TableCell className="text-sm">{p.onlinePlayers}/{p.maxPlayers}</TableCell></TableRow>
              ))}
              {!data.proxies.length && <TableRow><TableCell colSpan={4} className="py-8 text-center text-muted-foreground">No proxies running.</TableCell></TableRow>}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>Backends</CardTitle></CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow><TableHead>Service</TableHead><TableHead>Task</TableHead><TableHead>Address</TableHead><TableHead>Players</TableHead></TableRow></TableHeader>
            <TableBody>
              {data.backends.map((b) => (
                <TableRow key={b.id}><TableCell className="font-medium">{b.id}</TableCell><TableCell className="text-sm text-muted-foreground">{b.task}</TableCell><TableCell className="font-mono text-xs">{b.host}:{b.port}</TableCell><TableCell className="text-sm">{b.onlinePlayers}</TableCell></TableRow>
              ))}
              {!data.backends.length && <TableRow><TableCell colSpan={4} className="py-8 text-center text-muted-foreground">No running backends.</TableCell></TableRow>}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}
