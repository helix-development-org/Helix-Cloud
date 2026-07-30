import { useCountUp } from "@/lib/anim"

/** Animated numeric value: counts from the previously rendered value to `value` on every change. */
export function CountUp({ value, digits = 0, unit }: { value: number; digits?: number; unit?: string }) {
  const shown = useCountUp(value, digits)
  return <>{unit ? `${shown} ${unit}` : shown}</>
}
