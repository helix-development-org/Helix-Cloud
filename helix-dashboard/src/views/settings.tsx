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

/** Editor for the global network name ({network}) and prefix ({prefix}). */
function NetworkPrefixCard() {
  const [values, setValues] = useState<{ name: string; prefix: string } | null>(null)

  useEffect(() => {
    api<{ entries: { key: string; values: Record<string, string>; defaults: Record<string, string> }[] }>("/translations")
      .then((view) => {
        const effective = (key: string) => {
          const entry = view.entries.find((e) => e.key === `helix.translations.network.${key}`)
          return entry?.values["en"] ?? entry?.defaults["en"] ?? ""
        }
        setValues({ name: effective("name"), prefix: effective("prefix") })
      })
      .catch(() => setValues(null))
  }, [])

  if (values === null) return null

  const save = async (key: "name" | "prefix") => {
    try {
      await api("/translations", {
        method: "POST",
        body: { key: `helix.translations.network.${key}`, language: "en", value: values[key] },
      })
      toast.success(`Network ${key} saved — usable as {${key === "name" ? "network" : "prefix"}} everywhere`)
    } catch (e) {
      toast.error((e as Error).message)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Network</CardTitle>
        <div className="text-sm text-muted-foreground">
          Available as <code className="rounded bg-secondary px-1">{"{network}"}</code> and{" "}
          <code className="rounded bg-secondary px-1">{"{prefix}"}</code> in every configurable message,
          MOTD, tablist and disconnect screen. MiniMessage and &amp; codes supported; changes apply instantly.
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="flex items-center gap-3">
          <span className="w-16 shrink-0 text-xs text-muted-foreground">Name</span>
          <Input className="font-mono text-xs" value={values.name}
            onChange={(e) => setValues({ ...values, name: e.target.value })}
            onKeyDown={(e) => e.key === "Enter" && save("name")} placeholder="MythicMC" />
          <Button size="sm" onClick={() => save("name")}>Save</Button>
        </div>
        <div className="flex items-center gap-3">
          <span className="w-16 shrink-0 text-xs text-muted-foreground">Prefix</span>
          <Input className="font-mono text-xs" value={values.prefix}
            onChange={(e) => setValues({ ...values, prefix: e.target.value })}
            onKeyDown={(e) => e.key === "Enter" && save("prefix")}
            placeholder="<gradient:#8b5cf6:#38bdf8><bold>Helix</bold></gradient> »" />
          <Button size="sm" onClick={() => save("prefix")}>Save</Button>
        </div>
      </CardContent>
    </Card>
  )
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return <div><div className="text-xs text-muted-foreground">{label}</div><div className="mt-0.5">{value}</div></div>
}
