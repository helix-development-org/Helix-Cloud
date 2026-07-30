import { Bar } from "@/components/charts/bar"
import { BarChart } from "@/components/charts/bar-chart"
import { BarXAxis } from "@/components/charts/bar-x-axis"
import { ChartTooltip } from "@/components/charts/tooltip"
import type { ServiceInfo } from "@/lib/api"

/**
 * Bar chart of the running services' player distribution, one bar per task
 * (a Bklit bar chart) — shows at a glance where the players actually are.
 * Renders nothing when no running service reports players, so the overview
 * does not carry an empty chart on a fresh network.
 */
export function TaskDistributionChart({ services }: { services: ServiceInfo[] }) {
  const byTask = new Map<string, { players: number; services: number }>()
  services
    .filter((s) => s.state === "RUNNING")
    .forEach((s) => {
      const entry = byTask.get(s.taskName) ?? { players: 0, services: 0 }
      entry.players += Math.max(0, s.onlinePlayers)
      entry.services += 1
      byTask.set(s.taskName, entry)
    })
  const data = [...byTask.entries()]
    .map(([name, entry]) => ({ name, players: entry.players, services: entry.services }))
    .sort((a, b) => b.players - a.players)
  if (!data.length || data.every((d) => d.players === 0)) return null
  return (
    <BarChart data={data} xDataKey="name" aspectRatio="4 / 1" margin={{ top: 8, right: 8, bottom: 24, left: 8 }}>
      <Bar dataKey="players" fill="var(--chart-1)" />
      <BarXAxis showAllLabels />
      <ChartTooltip
        showDatePill={false}
        rows={(point) => [
          { label: "players", value: String(point.players), color: "var(--chart-1)" },
          { label: "services", value: String(point.services), color: "var(--chart-2)" },
        ]}
      />
    </BarChart>
  )
}
