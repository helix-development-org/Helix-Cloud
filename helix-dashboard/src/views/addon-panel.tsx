import { useEffect, useRef, useState } from "react"
import { toast } from "sonner"
import { api, type ActionResult } from "@/lib/api"

/** Bridge runtime injected into every addon panel iframe, defined before the
 *  panel's own scripts so `Helix.action/ready/toast` are always available. */
const RUNTIME = `
  window.Helix = {
    _seq: 0, _pending: {},
    action: function(name) {
      var args = Array.prototype.slice.call(arguments, 1).map(String);
      var id = ++this._seq, self = this;
      return new Promise(function(res) {
        self._pending[id] = res;
        parent.postMessage({ type: "helix:action", id: id, name: name, args: args }, "*");
      });
    },
    toast: function(message) { parent.postMessage({ type: "helix:toast", message: String(message) }, "*"); },
    ready: function(fn) {
      if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", fn);
      else setTimeout(fn, 0);
    }
  };
  window.addEventListener("message", function(e) {
    var d = e.data || {};
    if (d.type === "helix:result" && window.Helix._pending[d.id]) {
      window.Helix._pending[d.id](d.result); delete window.Helix._pending[d.id];
    }
  });
`

interface Palette {
  bg: string; surface: string; surface2: string; line: string; ink: string; ink2: string; ink3: string
  accent: string; accentInk: string; good: string; warn: string; bad: string
}

const DARK: Palette = {
  bg: "#141416", surface: "#1b1b1e", surface2: "#232327", line: "rgba(255,255,255,0.10)",
  ink: "#fafafa", ink2: "#d4d4d8", ink3: "#a1a1aa", accent: "#8b5cf6", accentInk: "#f5f3ff",
  good: "#34d399", warn: "#fbbf24", bad: "#f87171",
}
const LIGHT: Palette = {
  bg: "#ffffff", surface: "#ffffff", surface2: "#f4f4f5", line: "#e4e4e7",
  ink: "#18181b", ink2: "#3f3f46", ink3: "#71717a", accent: "#7c3aed", accentInk: "#ffffff",
  good: "#059669", warn: "#b45309", bad: "#dc2626",
}

/** Stylesheet implementing the panel component classes (card/btn/table/badge/…)
 *  in the given palette, so existing addon panels render consistently. */
function panelCss(p: Palette): string {
  return `
  :root{color-scheme:${p === DARK ? "dark" : "light"};
    --bg:${p.bg};--surface:${p.surface};--surface-2:${p.surface2};--line:${p.line};
    --ink:${p.ink};--ink-2:${p.ink2};--ink-3:${p.ink3};--accent:${p.accent};
    --good:${p.good};--warn:${p.warn};--bad:${p.bad};--radius:12px;--radius-sm:8px;
    --mono:ui-monospace,SFMono-Regular,Menlo,monospace;}
  *{box-sizing:border-box}
  body{margin:0;padding:20px;background:var(--bg);color:var(--ink);
    font-family:ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;font-size:14px;line-height:1.5}
  h2{font-size:15px;font-weight:600;margin:0}
  label{display:block;font-size:12px;color:var(--ink-3);margin-bottom:5px;font-weight:500}
  a{color:var(--accent)}
  .card{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);margin-bottom:16px;overflow:hidden}
  .card-head{display:flex;align-items:center;gap:10px;padding:14px 16px;border-bottom:1px solid var(--line)}
  .card-body{padding:16px}
  .sub{font-size:12px;color:var(--ink-3)}
  .right{margin-left:auto;display:flex;gap:8px}
  .row{display:flex;flex-wrap:wrap;align-items:center;gap:10px}
  .grid{display:grid;gap:14px}
  .grid.c2{grid-template-columns:repeat(auto-fit,minmax(240px,1fr))}
  .field{display:flex;flex-direction:column}
  .wide{grid-column:1/-1}
  .muted{color:var(--ink-3)}
  .mono{font-family:var(--mono);font-size:12.5px}
  .empty{padding:26px 12px;text-align:center;color:var(--ink-3);font-size:13px}
  input,textarea,select{width:100%;background:transparent;color:var(--ink);border:1px solid var(--line);
    border-radius:var(--radius-sm);padding:8px 11px;font:inherit;outline:none;transition:border-color .15s}
  input:focus,textarea:focus,select:focus{border-color:var(--accent)}
  textarea{resize:vertical;font-family:var(--mono);font-size:12.5px}
  .btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;cursor:pointer;white-space:nowrap;
    border:1px solid transparent;border-radius:var(--radius-sm);padding:8px 14px;font:inherit;font-weight:500;font-size:13px;
    background:var(--accent);color:${p.accentInk};transition:opacity .15s,background .15s}
  .btn:hover{opacity:.9}
  .btn.sm{padding:5px 10px;font-size:12px}
  .btn.subtle,.btn.neutral{background:var(--surface-2);color:var(--ink);border-color:var(--line)}
  .btn.danger{background:var(--bad);color:#fff}
  .btn.good{background:var(--good);color:#04120c}
  table{width:100%;border-collapse:collapse;font-size:13px}
  th{text-align:left;padding:8px 12px;color:var(--ink-3);font-size:11px;font-weight:600;
    text-transform:uppercase;letter-spacing:.04em;border-bottom:1px solid var(--line)}
  td{padding:9px 12px;border-bottom:1px solid var(--line)}
  tr:last-child td{border-bottom:0}
  strong{font-weight:600}
  .badge{display:inline-flex;align-items:center;border-radius:6px;padding:2px 8px;font-size:11.5px;font-weight:500;margin-right:6px}
  .badge.good{background:color-mix(in srgb,var(--good) 18%,transparent);color:var(--good)}
  .badge.warn{background:color-mix(in srgb,var(--warn) 18%,transparent);color:var(--warn)}
  .badge.bad,.badge.danger{background:color-mix(in srgb,var(--bad) 18%,transparent);color:var(--bad)}
  .badge.neutral{background:var(--surface-2);color:var(--ink-3)}
  .chip,.tag{display:inline-flex;align-items:center;border-radius:6px;padding:2px 8px;font-size:11.5px;
    background:var(--surface-2);color:var(--ink-2);margin-right:6px}
  `
}

/** Renders an addon-contributed dashboard panel in a sandboxed iframe with a
 *  postMessage action/toast bridge; the session token never enters the iframe. */
export function AddonPanelView({ panelId }: { panelId: string }) {
  const [html, setHtml] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const frameRef = useRef<HTMLIFrameElement>(null)

  useEffect(() => {
    setHtml(null)
    setError(null)
    api<{ html: string }>(`/panels/${panelId}`).then((p) => setHtml(p.html)).catch((e) => setError(e.message))
  }, [panelId])

  useEffect(() => {
    const handler = async (e: MessageEvent) => {
      const d = e.data || {}
      if (d.type === "helix:toast") {
        toast(String(d.message))
        return
      }
      if (d.type !== "helix:action") return
      let result: ActionResult
      try {
        result = await api<ActionResult>("/actions", { method: "POST", body: { action: d.name, arguments: d.args } })
      } catch (err) {
        result = { success: false, lines: [(err as Error).message] }
      }
      frameRef.current?.contentWindow?.postMessage({ type: "helix:result", id: d.id, result }, "*")
    }
    window.addEventListener("message", handler)
    return () => window.removeEventListener("message", handler)
  }, [])

  if (error) return <div className="text-sm text-destructive">{error}</div>
  if (html === null) return <div className="text-sm text-muted-foreground">Loading…</div>

  const dark = document.documentElement.classList.contains("dark")
  const srcDoc = `<style>${panelCss(dark ? DARK : LIGHT)}</style><script>${RUNTIME}</script>${html}`

  return (
    <iframe
      ref={frameRef}
      title={panelId}
      sandbox="allow-scripts allow-forms allow-modals"
      srcDoc={srcDoc}
      className="h-[calc(100vh-8rem)] w-full rounded-xl border bg-card"
    />
  )
}
