import { useEffect, useState } from "react"
import { File, Folder, FolderOpen } from "lucide-react"
import { toast } from "sonner"
import { api } from "@/lib/api"
import { ago } from "@/lib/format"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Textarea } from "@/components/ui/textarea"

interface Entry {
  name: string
  directory: boolean
  sizeBytes: number
  modifiedAtEpochMs: number
}

function size(bytes: number): string {
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + " MiB"
  if (bytes >= 1024) return Math.round(bytes / 1024) + " KiB"
  return bytes + " B"
}

/** File manager over service workspaces and templates. */
export function FilesView() {
  const [roots, setRoots] = useState<string[]>([])
  const [root, setRoot] = useState("")
  const [path, setPath] = useState("")
  const [entries, setEntries] = useState<Entry[]>([])
  const [editing, setEditing] = useState<{ path: string; content: string } | null>(null)

  useEffect(() => {
    api<string[]>("/files/roots").then((r) => { setRoots(r); if (r.length && !root) setRoot(r[0]) }).catch((e) => toast.error(e.message))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const load = (r = root, p = path) => {
    if (!r) return
    api<Entry[]>(`/files/list?root=${encodeURIComponent(r)}&path=${encodeURIComponent(p)}`)
      .then(setEntries)
      .catch((e) => toast.error((e as Error).message))
  }
  useEffect(() => { setEditing(null); load(root, path) }, [root, path]) // eslint-disable-line react-hooks/exhaustive-deps

  const open = async (entry: Entry) => {
    const target = path ? `${path}/${entry.name}` : entry.name
    if (entry.directory) { setPath(target); return }
    try {
      const file = await api<{ content: string }>(`/files/content?root=${encodeURIComponent(root)}&path=${encodeURIComponent(target)}`)
      setEditing({ path: target, content: file.content })
    } catch (e) { toast.error((e as Error).message) }
  }
  const save = async () => {
    if (!editing) return
    try {
      await api("/files/content", { method: "PUT", body: { root, path: editing.path, content: editing.content } })
      toast.success("Saved " + editing.path)
    } catch (e) { toast.error((e as Error).message) }
  }
  const del = async (entry: Entry) => {
    const target = path ? `${path}/${entry.name}` : entry.name
    if (!confirm(`Delete ${target}${entry.directory ? " (recursively)" : ""}?`)) return
    try {
      await api(`/files?root=${encodeURIComponent(root)}&path=${encodeURIComponent(target)}`, { method: "DELETE" })
      toast.success("Deleted " + target)
      load()
    } catch (e) { toast.error((e as Error).message) }
  }

  const crumbs = path ? path.split("/") : []

  return (
    <div className="flex flex-col gap-5">
      <Card>
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <div className="w-64">
            <Select value={root} onValueChange={(r) => { setRoot(r); setPath("") }}>
              <SelectTrigger><SelectValue placeholder="Root…" /></SelectTrigger>
              <SelectContent>{roots.map((r) => <SelectItem key={r} value={r}>{r}</SelectItem>)}</SelectContent>
            </Select>
          </div>
          <div className="flex items-center gap-1 font-mono text-xs text-muted-foreground">
            <button className="hover:text-foreground" onClick={() => setPath("")}>{root || "—"}</button>
            {crumbs.map((c, i) => (
              <span key={i}> / <button className="hover:text-foreground" onClick={() => setPath(crumbs.slice(0, i + 1).join("/"))}>{c}</button></span>
            ))}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader><TableRow><TableHead>Name</TableHead><TableHead>Size</TableHead><TableHead>Modified</TableHead><TableHead /></TableRow></TableHeader>
            <TableBody>
              {path && (
                <TableRow className="cursor-pointer" onClick={() => setPath(crumbs.slice(0, -1).join("/"))}>
                  <TableCell colSpan={4}><span className="flex items-center gap-2 text-muted-foreground"><FolderOpen className="size-4" /> ..</span></TableCell>
                </TableRow>
              )}
              {entries.map((e) => (
                <TableRow key={e.name} className="cursor-pointer" onClick={() => open(e)}>
                  <TableCell><span className="flex items-center gap-2">{e.directory ? <Folder className="size-4 text-primary" /> : <File className="size-4 text-muted-foreground" />} {e.name}</span></TableCell>
                  <TableCell className="text-sm text-muted-foreground">{e.directory ? "—" : size(e.sizeBytes)}</TableCell>
                  <TableCell className="text-sm text-muted-foreground">{ago(e.modifiedAtEpochMs)}</TableCell>
                  <TableCell onClick={(ev) => ev.stopPropagation()}>
                    <div className="flex justify-end"><Button size="sm" variant="destructive" onClick={() => del(e)}>Delete</Button></div>
                  </TableCell>
                </TableRow>
              ))}
              {!entries.length && !path && (
                <TableRow><TableCell colSpan={4} className="py-10 text-center text-muted-foreground">{root ? "Empty directory." : "No workspaces or templates yet."}</TableCell></TableRow>
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {editing && (
        <Card>
          <CardContent className="flex flex-col gap-3 p-4">
            <div className="flex items-center justify-between">
              <span className="font-mono text-xs">{root}/{editing.path}</span>
              <div className="flex gap-2">
                <Button size="sm" variant="ghost" onClick={() => setEditing(null)}>Close</Button>
                <Button size="sm" onClick={save}>Save</Button>
              </div>
            </div>
            <Textarea className="min-h-[45vh] font-mono text-xs" value={editing.content}
              onChange={(ev) => setEditing({ ...editing, content: ev.target.value })} />
          </CardContent>
        </Card>
      )}
    </div>
  )
}
