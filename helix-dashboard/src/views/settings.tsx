import { useEffect, useState } from "react"
import { toast } from "sonner"
import { api, token, type Identity } from "@/lib/api"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"

/** Session info, the global network prefix and the current bearer token. */
export function SettingsView({ identity, version }: { identity: Identity; version: string }) {
  return (
    <div className="flex flex-col gap-5">
      <Card>
        <CardHeader><CardTitle>Session</CardTitle></CardHeader>
        <CardContent className="grid gap-3 text-sm sm:grid-cols-2">
          <Row label="Signed in as" value={identity.name} />
          <Row label="Access" value={identity.admin ? <Badge variant="success">admin (full)</Badge> : <Badge variant="secondary">{identity.views.length} views</Badge>} />
          <Row label="Node version" value={"v" + version} />
          <Row label="Allowed views" value={identity.admin ? "all" : identity.views.join(", ") || "—"} />
        </CardContent>
      </Card>
      <NetworkPrefixCard />
      <Card>
        <CardHeader><CardTitle>Bearer token</CardTitle></CardHeader>
        <CardContent className="flex items-center gap-3">
          <code className="flex-1 truncate rounded-md bg-secondary px-3 py-2 font-mono text-xs">{token.slice(0, 12)}…{token.slice(-6)}</code>
          <Button size="sm" variant="outline" onClick={() => { navigator.clipboard?.writeText(token); toast.success("Token copied") }}>Copy</Button>
        </CardContent>
      </Card>
    </div>
  )
}

/** Editor for the global {prefix} placeholder usable in every message. */
function NetworkPrefixCard() {
  const [prefix, setPrefix] = useState<string | null>(null)

  useEffect(() => {
    api<Record<string, Record<string, string>>>("/messages")
      .then((all) => setPrefix(all["network"]?.["prefix"] ?? ""))
      .catch(() => setPrefix(null))
  }, [])

  if (prefix === null) return null

  const save = async () => {
    try {
      await api("/messages", { method: "POST", body: { addonId: "network", key: "prefix", value: prefix } })
      toast.success("Network prefix saved — usable as {prefix} in every message")
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Network prefix</CardTitle>
        <div className="text-sm text-muted-foreground">
          Available as <code className="rounded bg-secondary px-1">{"{prefix}"}</code> in every configurable message,
          MOTD, tablist and disconnect screen. MiniMessage and &amp; codes supported.
        </div>
      </CardHeader>
      <CardContent className="flex items-center gap-3">
        <Input className="font-mono text-xs" value={prefix} onChange={(e) => setPrefix(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && save()} placeholder="<gradient:#8b5cf6:#38bdf8><bold>Helix</bold></gradient> »" />
        <Button size="sm" onClick={save}>Save</Button>
      </CardContent>
    </Card>
  )
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return <div><div className="text-xs text-muted-foreground">{label}</div><div className="mt-0.5">{value}</div></div>
}
