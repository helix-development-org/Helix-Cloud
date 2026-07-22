import { toast } from "sonner"
import { api } from "@/lib/api"
import { usePoll } from "@/lib/use-poll"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Textarea } from "@/components/ui/textarea"

type Messages = Record<string, Record<string, string>>

/** Configurable addon & proxy messages, multi-line (MiniMessage / & codes). */
export function MessagesView() {
  const { data, reload } = usePoll(() => api<Messages>("/messages"), 0)

  const save = async (addonId: string, key: string, value: string) => {
    try { await api("/messages", { method: "POST", body: { addonId, key, value } }); toast.success("Saved " + key) }
    catch (e) { toast.error((e as Error).message) }
  }
  const reset = async (addonId: string, key: string) => {
    try { await api("/messages", { method: "POST", body: { addonId, key, reset: true } }); toast.success("Reset " + key); reload() }
    catch (e) { toast.error((e as Error).message) }
  }

  const owners = Object.keys(data ?? {})
  if (data && !owners.length) return <div className="text-sm text-muted-foreground">No configurable messages.</div>

  return (
    <div className="flex flex-col gap-5">
      {owners.map((owner) => (
        <Card key={owner}>
          <CardHeader><CardTitle className="font-mono text-sm">{owner}</CardTitle></CardHeader>
          <CardContent className="flex flex-col gap-4">
            {Object.entries(data![owner]).map(([key, value]) => (
              <MessageRow key={key} k={key} initial={value} onSave={(v) => save(owner, key, v)} onReset={() => reset(owner, key)} />
            ))}
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

function MessageRow({ k, initial, onSave, onReset }: { k: string; initial: string; onSave: (v: string) => void; onReset: () => void }) {
  let current = initial
  return (
    <div className="grid gap-2 sm:grid-cols-[200px_1fr_auto] sm:items-start">
      <div className="pt-2 font-mono text-xs text-muted-foreground">{k}</div>
      <Textarea className="font-mono text-xs" defaultValue={initial} rows={Math.min(8, Math.max(1, initial.split("\n").length))}
        onChange={(e) => (current = e.target.value)} />
      <div className="flex gap-2">
        <Button size="sm" onClick={() => onSave(current)}>Save</Button>
        <Button size="sm" variant="ghost" onClick={onReset}>Reset</Button>
      </div>
    </div>
  )
}
