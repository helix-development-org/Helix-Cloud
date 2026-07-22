import { useEffect, useState } from "react"

/**
 * Loads data via [loader] on mount and every [intervalMs] (0 = once).
 * Returns the latest value, an error and a manual reload function.
 *
 * @param loader async data source.
 * @param intervalMs poll interval in milliseconds; 0 disables polling.
 * @param deps re-subscribe dependencies.
 */
export function usePoll<T>(loader: () => Promise<T>, intervalMs = 0, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let active = true
    const run = () =>
      loader()
        .then((d) => active && (setData(d), setError(null)))
        .catch((e) => active && setError(e.message))
    run()
    if (intervalMs > 0) {
      const id = setInterval(run, intervalMs)
      return () => {
        active = false
        clearInterval(id)
      }
    }
    return () => {
      active = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, tick, ...deps])

  return { data, error, reload: () => setTick((t) => t + 1) }
}
