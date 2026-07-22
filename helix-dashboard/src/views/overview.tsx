import { Server, Users, Network, ListChecks } from "lucide-react"
import { api, type EventEntry, type MetricSample, type Overview, type ProxyView, type ServiceInfo } from "@/lib/api"
import { usePoll } from "@/lib/use-poll"
import { ago } from "@/lib/format"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { MetricChart } from "@/components/metric-chart"

/** Overview: stat tiles, trend charts and recent activity. */
export function OverviewView() {
  const { data } = usePoll(async () => {
    const [ov, services, events, proxy, metrics] = await Promise.all([
      api<Overview>("/platform/overview"),
      api<ServiceInfo[]>("/services"),
      api<EventEntry[]>("/events?limit=8"),
      api<ProxyView>("/proxy"),
      api<MetricSample[]>("/metrics?limit=240").catch(() => [] as MetricSample[]),
    ])
    return { ov, services, events, proxy, metrics }
  }, 5000)

  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>
  const { ov, events, proxy, metrics } = data
  const hasTps = metrics.some((m) => m.avgTps != null)

  return (
    <div className="flex flex-col gap-5">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Tile label="Players online" value={ov.onlinePlayers} sub={`of ${ov.maxPlayers} slots`} icon={<Users className="size-4" />} accent />
        <Tile label="Services" value={`${ov.servicesRunning} / ${ov.servicesTotal}`} sub="running / total" icon={<Server className="size-4" />} />
        <Tile label="Tasks" value={ov.taskCount} sub="blueprints" icon={<ListChecks className="size-4" />} />
        <Tile label="Network" value={proxy.maintenance ? "Maintenance" : "Operational"}
          sub={`${proxy.proxies.length} proxies · ${proxy.backends.length} backends`} icon={<Network className="size-4" />} />
      </div>

      <Card>
        <CardHeader><CardTitle>Trends</CardTitle></CardHeader>
        <CardContent className="grid gap-6 sm:grid-cols-3">
          <ChartBox label="Players online" value={ov.onlinePlayers}>
            <MetricChart samples={metrics} dataKey="onlinePlayers" color="var(--chart-1)" />
          </ChartBox>
          <ChartBox label="Services running" value={ov.servicesRunning}>
            <MetricChart samples={metrics} dataKey="servicesRunning" color="var(--chart-2)" />
          </ChartBox>
          {hasTps && (
            <ChartBox label="Avg TPS" value={metrics.at(-1)?.avgTps?.toFixed(1) ?? "—"}>
              <MetricChart samples={metrics} dataKey="avgTps" color="var(--chart-3)" />
            </ChartBox>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>Recent activity</CardTitle></CardHeader>
        <CardContent className="flex flex-col gap-2">
          {events.length ? events.map((e, i) => (
            <div key={i} className="flex items-center gap-3 text-sm">
              <Badge variant={e.level === "warn" ? "warning" : e.level === "error" ? "destructive" : "secondary"}>{e.category}</Badge>
              <span className="flex-1 truncate">{e.message}</span>
              <span className="text-xs text-muted-foreground">{ago(e.epochMs)}</span>
            </div>
          )) : <div className="text-sm text-muted-foreground">No activity yet.</div>}
        </CardContent>
      </Card>
    </div>
  )
}

function Tile({ label, value, sub, icon, accent }: { label: string; value: React.ReactNode; sub: string; icon: React.ReactNode; accent?: boolean }) {
  return (
    <Card className={accent ? "border-primary/30" : ""}>
      <CardContent className="p-5">
        <div className="flex items-center justify-between text-muted-foreground">
          <span className="text-sm">{label}</span>{icon}
        </div>
        <div className="mt-2 text-2xl font-semibold">{value}</div>
        <div className="text-xs text-muted-foreground">{sub}</div>
      </CardContent>
    </Card>
  )
}

function ChartBox({ label, value, children }: { label: string; value: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="rounded-lg border bg-secondary/30 p-3">
      <div className="mb-1 flex items-baseline justify-between">
        <span className="text-sm text-muted-foreground">{label}</span>
        <span className="text-lg font-semibold">{value}</span>
      </div>
      {children}
    </div>
  )
}
