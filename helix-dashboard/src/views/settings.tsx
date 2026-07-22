import { toast } from "sonner"
import { token, type Identity } from "@/lib/api"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"

/** Session info and the current bearer token. */
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

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return <div><div className="text-xs text-muted-foreground">{label}</div><div className="mt-0.5">{value}</div></div>
}
