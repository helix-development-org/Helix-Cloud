import { Server, Users, Network, ListChecks, Gauge, Cpu, Activity, HardDrive } from "lucide-react"
import {
  api,
  type ApiStats,
  type EventEntry,
  type MetricSample,
  type NodeHealth,
  type Overview,
  type ProxyView,
  type ServiceInfo,
} from "@/lib/api"
import { usePoll } from "@/lib/use-poll"
import { ago, duration } from "@/lib/format"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { MetricChart } from "@/components/metric-chart"

/** Overview: stat tiles, live node health, trend charts grouped by area, and recent activity. */
export function OverviewView() {
  const { data } = usePoll(async () => {
    const [ov, services, events, proxy, metrics, apiStats, health] = await Promise.all([
      api<Overview>("/platform/overview"),
      api<ServiceInfo[]>("/services"),
      api<EventEntry[]>("/events?limit=8"),
      api<ProxyView>("/proxy"),
      api<MetricSample[]>("/metrics?limit=240").catch(() => [] as MetricSample[]),
      api<ApiStats>("/api-stats").catch(() => null),
      api<NodeHealth>("/health").catch(() => null),
    ])
    return { ov, services, events, proxy, metrics, apiStats, health }
  }, 5000)

  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>
  const { ov, events, proxy, metrics, apiStats, health } = data

  return (
    <div className="flex flex-col gap-5">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Tile label="Players online" value={ov.onlinePlayers} sub={`of ${ov.maxPlayers} slots`} icon={<Users className="size-4" />} accent />
        <Tile label="Services" value={`${ov.servicesRunning} / ${ov.servicesTotal}`} sub="running / total" icon={<Server className="size-4" />} />
        <Tile label="Tasks" value={ov.taskCount} sub="blueprints" icon={<ListChecks className="size-4" />} />
        <Tile label="Network" value={proxy.maintenance ? "Maintenance" : "Operational"}
          sub={`${proxy.proxies.length} proxies · ${proxy.backends.length} backends`} icon={<Network className="size-4" />} />
      </div>

      {health && (
        <Card>
          <CardHeader><CardTitle className="flex items-center gap-2"><Activity className="size-4" /> Node health</CardTitle></CardHeader>
          <CardContent className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
            <Stat label="Node CPU" value={fmt(health.nodeCpuPercent, 1, "%")} />
            <HeapTile usedMb={health.heapUsedMb} maxMb={health.heapMaxMb} pct={health.heapPercent} />
            <Stat
              label="System load"
              value={health.systemLoadAverage >= 0 ? `${health.systemLoadAverage.toFixed(2)} / ${health.availableProcessors}` : "—"}
              sub={health.systemLoadAverage >= 0 ? "load / cores" : "no data"}
            />
            <Stat label="Threads" value={`${health.threadCount}`} sub={`peak ${health.peakThreadCount}`} />
            <Stat label="GC" value={`${health.gcCount}`} sub={`${health.gcTimeMs} ms total`} />
            <Stat label="Uptime" value={duration(health.uptimeMs)} />
            <Stat label="Non-heap" value={fmt(health.nonHeapUsedMb, 0, "MB")} />
            <Stat label="Permission cache" value={`${health.permissionCacheSize}`} sub="entries" />
            <Stat label="Scheduled jobs" value={`${health.scheduledJobs}`} />
            <Stat label="Stale heartbeats" value={`${health.staleHeartbeats}`} tone={health.staleHeartbeats > 0 ? "warn" : undefined} />
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><Users className="size-4" /> Network</CardTitle></CardHeader>
        <CardContent className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          <TrendChart samples={metrics} dataKey="onlinePlayers" color="var(--chart-1)" label="Players online" />
          <TrendChart samples={metrics} dataKey="avgTps" color="var(--chart-3)" label="Avg TPS" digits={1} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><Cpu className="size-4" /> Node resources</CardTitle></CardHeader>
        <CardContent className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          <TrendChart samples={metrics} dataKey="nodeCpuPercent" color="var(--chart-1)" label="Node CPU" unit="%" digits={1} />
          <TrendChart samples={metrics} dataKey="nodeHeapUsedMb" color="var(--chart-2)" label="Node heap" unit="MB" />
          <TrendChart samples={metrics} dataKey="systemLoadAverage" color="var(--chart-3)" label="System load" digits={2} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><HardDrive className="size-4" /> Services</CardTitle></CardHeader>
        <CardContent className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
          <TrendChart samples={metrics} dataKey="servicesRunning" color="var(--chart-2)" label="Services running" />
          <TrendChart samples={metrics} dataKey="servicesCpuPercent" color="var(--chart-4)" label="Services CPU" unit="%" digits={1} />
          <TrendChart samples={metrics} dataKey="servicesMemoryUsedMb" color="var(--chart-5)" label="Services memory" unit="MB" />
        </CardContent>
      </Card>

      {apiStats && (
        <Card>
          <CardHeader><CardTitle className="flex items-center gap-2"><Gauge className="size-4" /> API performance</CardTitle></CardHeader>
          <CardContent className="flex flex-col gap-4">
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
              <Metric label="Avg response" value={`${apiStats.avgMs} ms`} />
              <Metric label="p95 response" value={`${apiStats.p95Ms} ms`} />
              <Metric label="Requests / min" value={apiStats.requestsPerMinute.toString()} />
              <Metric label="Error rate" value={`${apiStats.errorRate}%`} tone={apiStats.errorRate > 0 ? "bad" : undefined} />
              <Metric label="Total requests" value={apiStats.totalRequests.toLocaleString()} />
            </div>
            <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
              <TrendChart samples={metrics} dataKey="avgApiMs" color="var(--chart-4)" label="API response" unit="ms" digits={1} />
            </div>
          </CardContent>
        </Card>
      )}

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

/** Formats a numeric health value, rendering the `-1`/negative sentinel as an em dash. */
function fmt(v: number | null | undefined, digits = 0, unit = ""): string {
  if (v == null || v < 0) return "—"
  const n = digits ? v.toFixed(digits) : Math.round(v).toString()
  return unit ? `${n} ${unit}` : n
}

/** Latest real (non-null, ≥ 0) value in a metric series, or null if it never has data. */
function latestReal(samples: MetricSample[], key: keyof MetricSample): number | null {
  for (let i = samples.length - 1; i >= 0; i--) {
    const v = samples[i][key]
    if (typeof v === "number" && v >= 0) return v
  }
  return null
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

function Metric({ label, value, tone }: { label: string; value: string; tone?: "bad" }) {
  return (
    <div className="rounded-lg border bg-secondary/30 p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={"mt-1 text-xl font-semibold " + (tone === "bad" ? "text-destructive" : "")}>{value}</div>
    </div>
  )
}

/** Compact stat tile for the node-health grid; `tone` tints the value for warnings/errors. */
function Stat({ label, value, sub, tone }: { label: string; value: React.ReactNode; sub?: string; tone?: "warn" | "bad" }) {
  const toneClass = tone === "bad" ? "text-destructive" : tone === "warn" ? "text-[var(--warning)]" : ""
  return (
    <div className="rounded-lg border bg-secondary/30 p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className={"mt-1 text-lg font-semibold " + toneClass}>{value}</div>
      {sub && <div className="text-xs text-muted-foreground">{sub}</div>}
    </div>
  )
}

/** Heap tile with a utilisation meter coloured by pressure (green → amber → red). */
function HeapTile({ usedMb, maxMb, pct }: { usedMb: number; maxMb: number; pct: number }) {
  const known = pct >= 0 && maxMb >= 0
  const tone = pct >= 90 ? "var(--destructive)" : pct >= 75 ? "var(--warning)" : "var(--success)"
  return (
    <div className="rounded-lg border bg-secondary/30 p-3">
      <div className="text-xs text-muted-foreground">Heap</div>
      <div className="mt-1 text-lg font-semibold">{known ? `${usedMb} / ${maxMb} MB` : "—"}</div>
      <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-border">
        <div className="h-full rounded-full" style={{ width: `${known ? Math.min(100, Math.max(0, pct)) : 0}%`, background: tone }} />
      </div>
      <div className="mt-1 text-xs text-muted-foreground">{known ? `${pct.toFixed(0)}% used` : "no data"}</div>
    </div>
  )
}

/** Trend sparkline with a big latest value; renders a subtle placeholder when the series has no real data. */
function TrendChart({ samples, dataKey, color, label, unit, digits = 0 }: {
  samples: MetricSample[]
  dataKey: keyof MetricSample
  color: string
  label: string
  unit?: string
  digits?: number
}) {
  const latest = latestReal(samples, dataKey)
  if (latest == null) {
    return (
      <div className="rounded-lg border bg-secondary/30 p-3">
        <div className="mb-1 text-sm text-muted-foreground">{label}</div>
        <div className="flex h-16 items-center justify-center text-xs text-muted-foreground">no data yet</div>
      </div>
    )
  }
  const shown = (digits ? latest.toFixed(digits) : Math.round(latest).toString()) + (unit ? ` ${unit}` : "")
  return (
    <div className="rounded-lg border bg-secondary/30 p-3">
      <div className="mb-1 flex items-baseline justify-between">
        <span className="text-sm text-muted-foreground">{label}</span>
        <span className="text-lg font-semibold">{shown}</span>
      </div>
      <MetricChart samples={samples} dataKey={dataKey} color={color} unit={unit} digits={digits} />
    </div>
  )
}
