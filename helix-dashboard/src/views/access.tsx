import { Fragment, useEffect, useMemo, useState } from "react"
import { Lock, RotateCcw, TriangleAlert } from "lucide-react"
import { toast } from "sonner"
import { api, type AccessCatalog, type ActionResult } from "@/lib/api"
import { useIsAdmin } from "@/lib/access"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/** One group of `perm.access.matrix`. */
interface AccessGroup {
  name: string
  weight: number
  wildcard: boolean
  granted: string[]
  inherited: string[]
}
interface AccessMatrix {
  groups: AccessGroup[]
  extras: { node: string; label: string; description: string }[]
}

/** One row of the matrix. */
interface Row {
  node: string
  label: string
  description: string
  /** Controls are indented below the area they belong to. */
  control: boolean
  /** Admin-equivalent node: shown with a warning. */
  critical?: boolean
}

const VIEW_TITLES: Record<string, string> = {
  overview: "Übersicht", tasks: "Tasks", services: "Server", players: "Spieler", proxy: "Proxy", events: "Ereignisse",
  logs: "Node-Log", audit: "Audit", addons: "Addons", schedules: "Zeitpläne", backups: "Backups", files: "Dateien",
  translations: "Übersetzungen", settings: "Einstellungen", access: "Zugriff",
}

/**
 * Access matrix (view "Zugriff"): which permission group may see and change what in the dashboard. Rows are the
 * dashboard capabilities (each area, then its controls), the addon tabs and a few team rights; columns are the groups,
 * highest weight first. Groups holding a wildcard (owner, manager) see everything and are locked here.
 */
export function AccessView() {
  // Several nodes are admin-equivalent, so only full admins may change the matrix; everyone else may look.
  const canEdit = useIsAdmin()
  const [catalog, setCatalog] = useState<AccessCatalog | null>(null)
  const [matrix, setMatrix] = useState<AccessMatrix | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  const loadMatrix = async () => {
    const r = await api<ActionResult>("/actions", { method: "POST", body: { action: "perm.access.matrix", arguments: [] } })
    if (!r.success) throw new Error(r.lines[0] || "perm.access.matrix failed")
    setMatrix(JSON.parse(r.lines[0]) as AccessMatrix)
  }
  useEffect(() => {
    api<AccessCatalog>("/access/catalog").then(setCatalog).catch((e) => toast.error((e as Error).message))
    loadMatrix().catch((e) => toast.error((e as Error).message))
  }, [])

  const sections = useMemo(() => {
    if (!catalog || !matrix) return []
    const result: { title: string; rows: Row[] }[] = [
      { title: "Anmeldung", rows: [{ node: catalog.login, label: "Im Dashboard anmelden", description: "Ohne dieses Recht ist kein Login möglich.", control: false }] },
    ]
    const byView = new Map<string, Row[]>()
    for (const c of catalog.capabilities) {
      const rows = byView.get(c.view) ?? []
      rows.push({ node: c.node, label: c.label, description: c.description, control: c.group !== "Dashboard-Bereiche", critical: c.critical })
      byView.set(c.view, rows)
    }
    for (const [view, rows] of byView) result.push({ title: VIEW_TITLES[view] ?? view, rows })
    if (catalog.panels.length) {
      result.push({
        title: "Erweiterungen",
        rows: catalog.panels.map((p) => ({ node: p.node, label: p.label, description: `Tab „${p.label}“ (${p.id})`, control: false })),
      })
    }
    if (matrix.extras.length) {
      result.push({ title: "Team-Rechte", rows: matrix.extras.map((e) => ({ node: e.node, label: e.label, description: e.description, control: false })) })
    }
    return result
  }, [catalog, matrix])

  const toggle = async (group: AccessGroup, node: string, grant: boolean, critical?: boolean) => {
    if (grant && critical && !confirm(`„${node}“ ist admin-gleich: wer es hat, kann sich volle Kontrolle über den Node verschaffen. Wirklich an „${group.name}“ vergeben?`)) return
    const key = group.name + "|" + node
    setBusy(key)
    try {
      const r = await api<ActionResult>("/actions", { method: "POST", body: { action: "perm.access.set", arguments: [group.name, node, grant ? "on" : "off"] } })
      if (!r.success) toast.error(r.lines[0] || "Ändern fehlgeschlagen")
      await loadMatrix()
    } catch (e) {
      toast.error((e as Error).message)
    } finally {
      setBusy(null)
    }
  }
  const presets = async () => {
    if (!confirm("Voreinstellungen an Team-Gruppen und Tester vergeben, die noch nie welche bekommen haben?")) return
    try {
      const r = await api<ActionResult>("/actions", { method: "POST", body: { action: "perm.access.presets", arguments: [] } })
      toast.success(r.lines[0] || "erledigt")
      await loadMatrix()
    } catch (e) { toast.error((e as Error).message) }
  }

  if (!catalog || !matrix) return <div className="text-sm text-muted-foreground">Lade Zugriffsrechte…</div>
  const groups = matrix.groups

  return (
    <div className="flex flex-col gap-5">
      <Card>
        <CardHeader>
          <CardTitle>Wer darf was im Dashboard?</CardTitle>
          <div className="text-sm text-muted-foreground">
            Häkchen setzen oder entfernen ändert die Rechte der ganzen Gruppe sofort. Ansehen und Steuern sind getrennt:
            wer Server sieht, darf sie nicht automatisch stoppen oder in die Konsole schreiben. Gruppen mit allen Rechten
            (Wildcard) sind gesperrt; geerbte Rechte stammen aus einer Eltern-Gruppe. Mit <TriangleAlert className="inline size-3.5 text-[var(--warn,#ffb43a)]" /> markierte
            Rechte sind admin-gleich (Konsole, Dateien, Tasks, Addons, Zeitpläne, Zugriff) und werden nie automatisch vergeben.
          </div>
        </CardHeader>
        <CardContent className="flex items-center gap-3">
          {canEdit ? (
            <>
              <Button size="sm" variant="outline" onClick={presets}><RotateCcw className="size-4" /> Voreinstellungen vergeben</Button>
              <span className="text-xs text-muted-foreground">Nur für Gruppen, die noch nie Voreinstellungen bekommen haben und keine Dashboard-Rechte haben.</span>
            </>
          ) : (
            <span className="text-xs text-muted-foreground"><Lock className="mr-1 inline size-3" />Nur Admins können Rechte ändern – du siehst die Übersicht.</span>
          )}
        </CardContent>
      </Card>
      <Card>
        <CardContent className="overflow-x-auto p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="min-w-[260px]">Recht</TableHead>
                {groups.map((g) => (
                  <TableHead key={g.name} className="text-center">
                    <div className="font-bold">{g.name}</div>
                    {g.wildcard && <Badge variant="secondary" className="mt-1 text-[10px]"><Lock className="mr-1 size-3" />alles</Badge>}
                  </TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {sections.map((section) => (
                <Fragment key={section.title}>
                  <TableRow>
                    <TableCell colSpan={groups.length + 1} className="bg-muted/40 py-1.5 font-display text-xs tracking-[0.08em] text-gold">{section.title}</TableCell>
                  </TableRow>
                  {section.rows.map((row) => (
                    <TableRow key={row.node}>
                      <TableCell className={row.control ? "pl-8" : ""}>
                        <div className={row.control ? "text-sm" : "text-sm font-bold"}>
                          {row.label}
                          {row.critical && (
                            <Badge variant="outline" className="ml-2 border-[var(--warn,#ffb43a)] text-[10px] text-[var(--warn,#ffb43a)]">
                              <TriangleAlert className="mr-1 size-3" />admin-gleich
                            </Badge>
                          )}
                        </div>
                        <div className="text-xs text-muted-foreground">{row.description}</div>
                        <div className="font-mono text-[10px] text-muted-foreground/70">{row.node}</div>
                      </TableCell>
                      {groups.map((g) => {
                        const own = g.granted.includes(row.node)
                        const inherited = g.inherited.includes(row.node)
                        const locked = g.wildcard || (inherited && !own)
                        const checked = g.wildcard || own || inherited
                        const key = g.name + "|" + row.node
                        return (
                          <TableCell key={g.name} className="text-center">
                            <input type="checkbox" className="size-4 cursor-pointer accent-[var(--gold)] disabled:cursor-not-allowed disabled:opacity-50"
                              checked={checked} disabled={!canEdit || locked || busy === key}
                              title={g.wildcard ? "Wildcard: alle Rechte" : inherited && !own ? "geerbt" : row.node}
                              onChange={(e) => toggle(g, row.node, e.target.checked, row.critical)} />
                          </TableCell>
                        )
                      })}
                    </TableRow>
                  ))}
                </Fragment>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}
