import { Area, AreaChart, ResponsiveContainer, Tooltip, YAxis } from "recharts"
import type { MetricSample } from "@/lib/api"

/** Normalises a raw metric value: null/undefined and negative sentinels (-1) become gaps. */
function clean(v: MetricSample[keyof MetricSample]): number | null {
  return typeof v === "number" && v >= 0 ? v : null
}

/**
 * Small area chart of one metric over time, shadcn-styled. Missing points
 * (null, or the `-1` "unknown" sentinel on old samples) render as gaps rather
 * than dropping to zero.
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
  const data = samples.map((s) => ({ epochMs: s.epochMs, v: clean(s[dataKey]) }))
  const gid = `g-${String(dataKey)}`
  return (
    <ResponsiveContainer width="100%" height={64}>
      <AreaChart data={data} margin={{ top: 4, right: 0, bottom: 0, left: 0 }}>
        <defs>
          <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={color} stopOpacity={0.35} />
            <stop offset="100%" stopColor={color} stopOpacity={0} />
          </linearGradient>
        </defs>
        <YAxis hide domain={[0, "auto"]} />
        <Tooltip
          contentStyle={{
            background: "var(--popover)",
            border: "1px solid var(--border)",
            borderRadius: 8,
            fontSize: 12,
          }}
          labelFormatter={() => ""}
          formatter={(v: number) => [`${v.toFixed(digits)}${unit ? " " + unit : ""}`, String(dataKey)]}
        />
        <Area type="monotone" dataKey="v" stroke={color} strokeWidth={1.6}
          fill={`url(#${gid})`} isAnimationActive={false} connectNulls={false} />
      </AreaChart>
    </ResponsiveContainer>
  )
}
