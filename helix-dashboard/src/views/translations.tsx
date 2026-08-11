import { useMemo, useState } from "react"
import { toast } from "sonner"
import { api } from "@/lib/api"
import { usePoll } from "@/lib/use-poll"
import { minimessage } from "@/lib/cm-minimessage"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { CodeEditor } from "@/components/code-editor"
import { Input } from "@/components/ui/input"
import { MiniMessagePreview } from "@/components/minimessage-preview"

// One shared MiniMessage language instance reused by every row editor.
const MINIMESSAGE = minimessage()

interface TranslationEntry {
  key: string
  values: Record<string, string>
  defaults: Record<string, string>
}

interface TranslationsData {
  languages: string[]
  defaultLanguage: string
  entries: TranslationEntry[]
}

const KEY_PREFIX = "helix.translations."

/** Multilingual translation editor: every network, proxy and addon message. */
export function TranslationsView() {
  const { data, reload } = usePoll(() => api<TranslationsData>("/translations"), 0)
  const [language, setLanguage] = useState<string | null>(null)
  const [filter, setFilter] = useState("")
  const [newLanguage, setNewLanguage] = useState("")
  const [newKey, setNewKey] = useState("")

  const active = language ?? data?.defaultLanguage ?? "en"
  const entries = useMemo(() => {
    const query = filter.trim().toLowerCase()
    return (data?.entries ?? []).filter((e) =>
      !query ||
      e.key.toLowerCase().includes(query) ||
      (e.values[active] ?? e.defaults[active] ?? "").toLowerCase().includes(query),
    )
  }, [data, filter, active])

  if (!data) return <div className="text-sm text-muted-foreground">Loading…</div>

  const post = async (body: unknown, ok: string) => {
    try { await api("/translations", { method: "POST", body }); toast.success(ok); reload() }
    catch (e) { toast.error((e as Error).message) }
  }

  const addLanguage = async () => {
    if (!newLanguage.trim()) return
    try { await api("/translations/languages", { method: "POST", body: { language: newLanguage.trim() } }); toast.success(`Added ${newLanguage.trim()}`); setNewLanguage(""); reload() }
    catch (e) { toast.error((e as Error).message) }
  }
  const makeDefault = async (code: string) => {
    try { await api("/translations/languages", { method: "POST", body: { language: code, default: true } }); toast.success(`${code} is now the default`); reload() }
    catch (e) { toast.error((e as Error).message) }
  }
  const removeLanguage = async (code: string) => {
    try { await api(`/translations/languages/${code}`, { method: "DELETE" }); toast.success(`Removed ${code}`); if (language === code) setLanguage(null); reload() }
    catch (e) { toast.error((e as Error).message) }
  }
  const createKey = () => {
    const key = newKey.trim()
    if (!key) return
    const full = key.startsWith(KEY_PREFIX) ? key : `${KEY_PREFIX}custom.${key}`
    post({ key: full, language: active, value: "" }, `Created ${full}`)
    setNewKey("")
  }
  const remove = async (key: string) => {
    try { await api(`/translations/${encodeURIComponent(key)}`, { method: "DELETE" }); toast.success(`Deleted ${key}`); reload() }
    catch (e) { toast.error((e as Error).message) }
  }

  return (
    <div className="flex flex-col gap-5">
      <Card>
        <CardHeader><CardTitle>Languages</CardTitle></CardHeader>
        <CardContent className="flex flex-wrap items-center gap-2">
          {data.languages.map((code) => (
            <span key={code} className="flex items-center gap-1">
              <Button size="sm" variant={code === active ? "default" : "outline"} className="font-mono"
                onClick={() => setLanguage(code)}>
                {code}{code === data.defaultLanguage ? " ★" : ""}
              </Button>
              {code !== data.defaultLanguage && code === active && (
                <>
                  <Button size="sm" variant="ghost" title="Make default" onClick={() => makeDefault(code)}>★</Button>
                  <Button size="sm" variant="ghost" title="Remove language" onClick={() => removeLanguage(code)}>×</Button>
                </>
              )}
            </span>
          ))}
          <div className="ml-auto flex items-center gap-2">
            <Input className="w-24 font-mono text-xs" placeholder="fr" value={newLanguage}
              onChange={(e) => setNewLanguage(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && addLanguage()} />
            <Button size="sm" variant="outline" onClick={addLanguage}>Add language</Button>
          </div>
        </CardContent>
      </Card>

      <div className="flex items-center gap-3">
        <Input className="max-w-md" placeholder="Filter keys or text…" value={filter}
          onChange={(e) => setFilter(e.target.value)} />
        <div className="ml-auto flex items-center gap-2">
          <Input className="w-72 font-mono text-xs" placeholder={`${KEY_PREFIX}custom.my.key`} value={newKey}
            onChange={(e) => setNewKey(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && createKey()} />
          <Button size="sm" onClick={createKey}>Add key</Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-sm">
            {entries.length} keys — editing <span className="font-mono">{active}</span>
            {active !== data.defaultLanguage && (
              <span className="ml-2 text-xs font-normal text-muted-foreground">
                empty values fall back to {data.defaultLanguage}
              </span>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {entries.map((entry) => (
            <TranslationRow key={`${entry.key}:${active}`} entry={entry} language={active}
              onSave={(v) => post({ key: entry.key, language: active, value: v }, `Saved ${entry.key}`)}
              onReset={() => post({ key: entry.key, language: active, reset: true }, `Reset ${entry.key}`)}
              onDelete={() => remove(entry.key)} />
          ))}
          {!entries.length && <div className="text-sm text-muted-foreground">No matching keys.</div>}
        </CardContent>
      </Card>
    </div>
  )
}

function TranslationRow({ entry, language, onSave, onReset, onDelete }: {
  entry: TranslationEntry
  language: string
  onSave: (value: string) => void
  onReset: () => void
  onDelete: () => void
}) {
  const custom = entry.values[language]
  const fallback = entry.defaults[language] ?? ""
  const initial = custom ?? fallback
  const deletable = !Object.keys(entry.defaults).length
  const [current, setCurrent] = useState(initial)
  // Mounting a full CodeMirror editor per row is expensive; with hundreds of
  // keys that froze the page. Keep rows lightweight and only spin up the real
  // editor once a row is actually opened for editing.
  const [editing, setEditing] = useState(false)
  return (
    <div className="grid gap-2 lg:grid-cols-[340px_1fr_auto] lg:items-start">
      <div className="pt-2">
        <div className="break-all font-mono text-xs text-muted-foreground">{entry.key}</div>
        <div className="mt-1 flex gap-1">
          {custom !== undefined && <Badge variant="secondary" className="text-[10px]">edited</Badge>}
          {custom === undefined && entry.defaults[language] === undefined && (
            <Badge variant="outline" className="text-[10px]">no {language} value</Badge>
          )}
        </div>
      </div>
      <div className="flex flex-col gap-2">
        {editing ? (
          <CodeEditor language={MINIMESSAGE} value={current} onChange={setCurrent} autoFocus />
        ) : (
          <button
            type="button"
            onClick={() => setEditing(true)}
            className="min-h-[2.25rem] w-full whitespace-pre-wrap break-words rounded-md border border-input bg-transparent px-3 py-2 text-left font-mono text-xs leading-relaxed hover:border-ring focus:outline-none focus:ring-2 focus:ring-ring"
          >
            {current || <span className="text-muted-foreground">Click to edit…</span>}
          </button>
        )}
        <MiniMessagePreview value={current} />
      </div>
      <div className="flex gap-2">
        <Button size="sm" onClick={() => onSave(current)}>Save</Button>
        {custom !== undefined && <Button size="sm" variant="ghost" onClick={onReset}>Reset</Button>}
        {deletable && <Button size="sm" variant="ghost" className="text-destructive" onClick={onDelete}>Delete</Button>}
      </div>
    </div>
  )
}
