import { Area } from "@/components/charts/area"
import { AreaChart } from "@/components/charts/area-chart"
import { ChartTooltip } from "@/components/charts/tooltip"
import type { MetricSample } from "@/lib/api"

/** Whether a raw metric value is real data (null/undefined and negative sentinels like -1 are not). */
function isReal(v: MetricSample[keyof MetricSample]): v is number {
  return typeof v === "number" && v >= 0
}

/**
 * Small area chart of one metric over time, rendered with the Bklit chart
 * components (visx + motion). Missing points (null, or the `-1` "unknown"
 * sentinel on old samples) are dropped from the series — the time-based
 * x-scale keeps the remaining points correctly positioned, so a data gap
 * shows as a straight bridge rather than a false drop to zero.
 */
export function MetricChart({
  samples,
  dataKey,
  color,
  unit,
  digits = 0,
}: {
  samples: MetricSample[]
  dataKey: keyof MetricSample
  color: string
  /** Optional unit appended in the tooltip (e.g. "ms", "MB", "%"). */
  unit?: string
  /** Decimal places shown in the tooltip. */
  digits?: number
}) {
  const data = samples
    .filter((s) => isReal(s[dataKey]))
    .map((s) => ({ date: s.epochMs, v: s[dataKey] as number }))
  if (!data.length) {
    return <div className="flex h-16 items-center justify-center text-xs text-muted-foreground">no data yet</div>
  }
  return (
    <AreaChart
      data={data}
      xDataKey="date"
      margin={{ top: 4, right: 0, bottom: 0, left: 0 }}
      style={{ height: 64, aspectRatio: "auto" }}
      className="w-full"
      animationDuration={700}
    >
      <Area dataKey="v" fill={color} stroke={color} strokeWidth={1.6} fillOpacity={0.35} />
      <ChartTooltip
        showDatePill={false}
        dotSize={3}
        rows={(point) => [
          {
            label: String(dataKey),
            value: `${(point.v as number).toFixed(digits)}${unit ? " " + unit : ""}`,
            color,
          },
        ]}
      />
    </AreaChart>
  )
}
