import { token } from "./api"

/**
 * Streams server-sent events from an authenticated endpoint (EventSource
 * cannot send Authorization headers, so this uses fetch + ReadableStream).
 * Resolves when the server closes the stream; rejects on transport errors so
 * callers can fall back to polling.
 */
export async function streamLines(
  path: string,
  onLine: (line: string) => void,
  signal: AbortSignal,
): Promise<void> {
  const res = await fetch("/api/v1" + path, {
    headers: { Authorization: "Bearer " + token, Accept: "text/event-stream" },
    signal,
  })
  if (!res.ok || !res.body) throw new Error("stream unavailable (" + res.status + ")")
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ""
  for (;;) {
    const { done, value } = await reader.read()
    if (done) return
    buffer += decoder.decode(value, { stream: true })
    let end
    while ((end = buffer.indexOf("\n\n")) >= 0) {
      const event = buffer.slice(0, end)
      buffer = buffer.slice(end + 2)
      for (const raw of event.split("\n")) {
        if (raw.startsWith("data: ")) onLine(raw.slice(6))
        else if (raw.startsWith("data:")) onLine(raw.slice(5))
      }
    }
  }
}
