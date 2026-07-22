import { Area, AreaChart, ResponsiveContainer, Tooltip, YAxis } from "recharts"
import type { MetricSample } from "@/lib/api"

/** Small area chart of one metric over time, shadcn-styled. */
export function MetricChart({
  samples,
  dataKey,
  color,
}: {
  samples: MetricSample[]
  dataKey: keyof MetricSample
  color: string
}) {
  return (
    <ResponsiveContainer width="100%" height={64}>
      <AreaChart data={samples} margin={{ top: 4, right: 0, bottom: 0, left: 0 }}>
        <defs>
          <linearGradient id={`g-${dataKey}`} x1="0" y1="0" x2="0" y2="1">
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
          formatter={(v: number) => [v, String(dataKey)]}
        />
        <Area type="monotone" dataKey={dataKey as string} stroke={color} strokeWidth={1.6}
          fill={`url(#g-${dataKey})`} isAnimationActive={false} />
      </AreaChart>
    </ResponsiveContainer>
  )
}
